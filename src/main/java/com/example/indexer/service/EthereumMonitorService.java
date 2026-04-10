package com.example.indexer.service;

import com.example.indexer.entity.TransferEvent;
import com.example.indexer.repository.TransferEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import io.reactivex.disposables.Disposable;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class EthereumMonitorService {

    private final TransferEventRepository repository;
    private final String rpcUrl;
    private final List<String> monitorTokens;

    private Web3j web3j;
    private Disposable subscription;

    public EthereumMonitorService(
            TransferEventRepository repository,
            @Value("${ethereum.rpc-url}") String rpcUrl,
            @Value("${ethereum.monitor-tokens}") String monitorTokensStr) {
        this.repository = repository;
        this.rpcUrl = rpcUrl;
        this.monitorTokens = Arrays.asList(monitorTokensStr.split(","));
    }

    @PostConstruct
    public void startMonitoring() {
        log.info("Connecting to Ethereum RPC: {}", rpcUrl);
        this.web3j = Web3j.build(new HttpService(rpcUrl));

        // ERC-20 Transfer Event Signature: Transfer(address,address,uint256)
        String eventSignature = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

        EthFilter filter = new EthFilter(
                DefaultBlockParameterName.LATEST,
                DefaultBlockParameterName.LATEST,
                monitorTokens
        );
        filter.addSingleTopic(eventSignature);

        log.info("Started monitoring Transfer events for tokens: {}", monitorTokens);

        this.subscription = web3j.ethLogFlowable(filter).subscribe(logResult -> {
            try {
                org.web3j.protocol.core.methods.response.Log ethLog = logResult;
                
                List<String> topics = ethLog.getTopics();
                // Topic 1 = from, Topic 2 = to
                String from = "0x" + topics.get(1).substring(26);
                String to = "0x" + topics.get(2).substring(26);
                BigInteger value = Numeric.toBigInt(ethLog.getData());

                TransferEvent event = TransferEvent.builder()
                        .transactionHash(ethLog.getTransactionHash())
                        .blockNumber(ethLog.getBlockNumber())
                        .fromAddress(from)
                        .toAddress(to)
                        .amount(value)
                        .contractAddress(ethLog.getAddress())
                        .createdAt(LocalDateTime.now())
                        .build();

                repository.save(event);
                log.info("Saved Transfer: {} -> {} | Value: {} | Tx: {}", from, to, value, ethLog.getTransactionHash());
            } catch (Exception e) {
                log.error("Error processing log: {}", e.getMessage());
            }
        }, throwable -> log.error("Subscription error: {}", throwable.getMessage()));
    }

    @PreDestroy
    public void stopMonitoring() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        if (web3j != null) {
            web3j.shutdown();
        }
    }
}
