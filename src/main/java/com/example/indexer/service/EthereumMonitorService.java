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
 * 具備分岔偵測 (Re-org Handling) 功能的監控服務
 */
@Slf4j
@Service
public class EthereumMonitorService {

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
            // 1. 初始化檢查點：如果是重啟後第一次執行，先從資料庫恢復進度
            if (lastProcessedBlock.equals(BigInteger.ZERO)) {
                Optional<TransferEvent> lastEvent = repository.findFirstByOrderByBlockNumberDesc();
                if (lastEvent.isPresent()) {
                    lastProcessedBlock = lastEvent.get().getBlockNumber();
                    log.info("【系統重啟】從資料庫恢復進度，目前高度為: {}", lastProcessedBlock);
                }
            }

            // 2. 核心：分岔偵測 (Re-org Detection)
            if (!lastProcessedBlock.equals(BigInteger.ZERO)) {
                // 向節點詢問我們「手上最後一個區塊」在鏈上的真實雜湊
                String currentChainHash = web3j.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(lastProcessedBlock), false).send()
                        .getBlock().getHash();
                
                // 從資料庫找那一塊的紀錄 (隨便找該區塊的一筆事件即可)
                Optional<TransferEvent> dbEvent = repository.findFirstByOrderByBlockNumberDesc();
                
                if (dbEvent.isPresent() && !dbEvent.get().getBlockHash().equalsIgnoreCase(currentChainHash)) {
                    log.error("⚠️ 【偵測到分岔！】區塊 {} 的雜湊不匹配。本地: {}, 鏈上: {}", 
                        lastProcessedBlock, dbEvent.get().getBlockHash(), currentChainHash);
                    
                    // 執行回退：刪除資料庫中該區塊及以後的所有資料
                    repository.deleteByBlockNumberGreaterThanEqual(lastProcessedBlock);
                    log.warn("🔄 【自動回退】已清理區塊 {} 之後的所有髒資料", lastProcessedBlock);
                    
                    // 將進度往回調一格，下次 Poll 就會重新抓取正確的鏈
                    lastProcessedBlock = lastProcessedBlock.subtract(BigInteger.ONE);
                    return; 
                }
            }

            // 3. 取得最新安全高度
            BigInteger realLatestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger safeLatestBlock = realLatestBlock.subtract(BigInteger.valueOf(confirmations));
            
            // 初次啟動且資料庫為空的情況
            if (lastProcessedBlock.equals(BigInteger.ZERO)) {
                lastProcessedBlock = safeLatestBlock.subtract(BigInteger.valueOf(5));
            }

            if (safeLatestBlock.compareTo(lastProcessedBlock) <= 0) {
                return;
            }

            log.info("【掃描中】範圍: {} -> {} | 鏈高度: {}", 
                lastProcessedBlock.add(BigInteger.ONE), safeLatestBlock, realLatestBlock);

            // 4. 事件過濾與抓取
            String eventSignature = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(lastProcessedBlock.add(BigInteger.ONE)),
                    DefaultBlockParameter.valueOf(safeLatestBlock),
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
                // 分發非同步處理
                processor.processLogsAsync(logs);
            }

            // 5. 更新本地紀錄進度
            lastProcessedBlock = safeLatestBlock;

        } catch (Exception e) {
            log.error("監控任務異常: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stopMonitoring() {
        if (web3j != null) {
            web3j.shutdown();
        }
    }
}
