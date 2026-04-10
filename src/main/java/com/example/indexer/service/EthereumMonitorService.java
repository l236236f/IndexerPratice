package com.example.indexer.service;

import com.example.indexer.entity.TransferEvent;
import com.example.indexer.repository.TransferEventRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 具備分岔偵測與分段補抓 (Chunked Catch-up) 功能的專業級監控服務
 */
@Slf4j
@Service
public class EthereumMonitorService {

    // 定義單次抓取的最大區塊範圍，防止 RPC 節點回傳 "Log range too large" 錯誤
    private static final BigInteger MAX_POLL_RANGE = BigInteger.valueOf(100);

    private final TransferEventProcessor processor;
    private final TransferEventRepository repository;
    private final String rpcUrl;
    private final List<String> monitorTokens;
    private final int confirmations;

    private Web3j web3j;
    private BigInteger lastProcessedBlock = BigInteger.ZERO;

    public EthereumMonitorService(
            TransferEventProcessor processor,
            TransferEventRepository repository,
            @Value("${ethereum.rpc-url}") String rpcUrl,
            @Value("${ethereum.monitor-tokens}") String monitorTokensStr,
            @Value("${ethereum.confirmations:3}") int confirmations) {
        this.processor = processor;
        this.repository = repository;
        this.rpcUrl = rpcUrl.trim();
        this.confirmations = confirmations;
        this.monitorTokens = Arrays.stream(monitorTokensStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.web3j = Web3j.build(new HttpService(this.rpcUrl));
    }

    @Scheduled(fixedDelay = 10000)
    public void pollTransferEvents() {
        try {
            // 1. 初始化進度恢復
            if (lastProcessedBlock.equals(BigInteger.ZERO)) {
                Optional<TransferEvent> lastEvent = repository.findFirstByOrderByBlockNumberDesc();
                if (lastEvent.isPresent()) {
                    lastProcessedBlock = lastEvent.get().getBlockNumber();
                    log.info("【系統啟動】從資料庫恢復進度，目前高度為: {}", lastProcessedBlock);
                }
            }

            // 2. 分岔偵測 (Re-org Detection)
            if (!lastProcessedBlock.equals(BigInteger.ZERO)) {
                String currentChainHash = web3j.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(lastProcessedBlock), false).send()
                        .getBlock().getHash();
                
                Optional<TransferEvent> dbEvent = repository.findFirstByOrderByBlockNumberDesc();
                
                if (dbEvent.isPresent() && !dbEvent.get().getBlockHash().equalsIgnoreCase(currentChainHash)) {
                    log.error("⚠️ 【偵測到分岔！】區塊 {} 雜湊不匹配。本地: {}, 鏈上: {}", 
                        lastProcessedBlock, dbEvent.get().getBlockHash(), currentChainHash);
                    
                    repository.deleteByBlockNumberGreaterThanEqual(lastProcessedBlock);
                    log.warn("🔄 【自動回退】已清理區塊 {} 之後的所有髒資料", lastProcessedBlock);
                    
                    lastProcessedBlock = lastProcessedBlock.subtract(BigInteger.ONE);
                    return; 
                }
            }

            // 3. 確定最新安全高度與抓取範圍
            BigInteger realLatestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger safeLatestBlock = realLatestBlock.subtract(BigInteger.valueOf(confirmations));
            
            if (lastProcessedBlock.equals(BigInteger.ZERO)) {
                lastProcessedBlock = safeLatestBlock.subtract(BigInteger.valueOf(5));
            }

            if (safeLatestBlock.compareTo(lastProcessedBlock) <= 0) {
                return;
            }

            // 【關鍵：分段運算】計算本次掃描的終點區塊
            BigInteger potentialEndBlock = lastProcessedBlock.add(MAX_POLL_RANGE);
            // 如果 potentialEndBlock 超過了目前的安全高度，就只抓到安全高度為止
            BigInteger toBlock = potentialEndBlock.compareTo(safeLatestBlock) < 0 ? potentialEndBlock : safeLatestBlock;

            log.info("【掃描中】範圍: {} -> {} | 鏈高度: {} | 目前進度: {}%", 
                lastProcessedBlock.add(BigInteger.ONE), toBlock, realLatestBlock, 
                calculateProgress(lastProcessedBlock, safeLatestBlock));

            // 4. 事件抓取
            String eventSignature = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(lastProcessedBlock.add(BigInteger.ONE)),
                    DefaultBlockParameter.valueOf(toBlock),
                    monitorTokens
            );
            filter.addSingleTopic(eventSignature);

            var logsResponse = web3j.ethGetLogs(filter).send();
            if (logsResponse.hasError()) {
                log.error("RPC error: {}", logsResponse.getError().getMessage());
                return;
            }

            List<org.web3j.protocol.core.methods.response.Log> logs = logsResponse.getLogs().stream()
                    .map(l -> (org.web3j.protocol.core.methods.response.Log) l.get())
                    .toList();

            if (!logs.isEmpty()) {
                processor.processLogsAsync(logs);
            }

            // 5. 更新進度紀錄點
            lastProcessedBlock = toBlock;

        } catch (Exception e) {
            log.error("監控任務異常: {}", e.getMessage());
        }
    }

    /**
     * 計算資料同步進度 (輔助觀察)
     */
    private String calculateProgress(BigInteger current, BigInteger total) {
        if (total.equals(BigInteger.ZERO)) return "100";
        double progress = current.doubleValue() / total.doubleValue() * 100;
        return String.format("%.2f", progress);
    }

    @PreDestroy
    public void stopMonitoring() {
        if (web3j != null) {
            web3j.shutdown();
        }
    }
}
