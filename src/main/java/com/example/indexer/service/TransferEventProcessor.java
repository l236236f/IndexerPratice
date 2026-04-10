package com.example.indexer.service;

import com.example.indexer.entity.TransferEvent;
import com.example.indexer.repository.TransferEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 非同步日誌處理器
 * 負責將原始日誌解析為轉帳事件並存入資料庫
 */
@Slf4j
@Service
public class TransferEventProcessor {

    private final TransferEventRepository repository;

    public TransferEventProcessor(TransferEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 非同步處理日誌列表
     * @Async 註解會讓此方法在 Spring 的執行緒池中執行
     */
    @Async
    public void processLogsAsync(List<Log> logs) {
        // 取得當前執行緒名稱，驗證是否為非同步執行
        String threadName = Thread.currentThread().getName();
        log.info("【非同步處理】開始解析 {} 筆日誌 (執行緒: {})", logs.size(), threadName);

        for (Log ethLog : logs) {
            try {
                List<String> topics = ethLog.getTopics();
                
                // 解析與之前相同的邏輯
                String from = "0x" + topics.get(1).substring(26);
                String to = "0x" + topics.get(2).substring(26);
                BigInteger value = Numeric.toBigInt(ethLog.getData());

                TransferEvent event = TransferEvent.builder()
                        .transactionHash(ethLog.getTransactionHash())
                        .blockHash(ethLog.getBlockHash())
                        .blockNumber(ethLog.getBlockNumber())
                        .fromAddress(from)
                        .toAddress(to)
                        .amount(value)
                        .contractAddress(ethLog.getAddress())
                        .createdAt(LocalDateTime.now())
                        .build();

                repository.save(event);
                log.info("【儲存成功】來自執行緒 {}: 區塊 {} 的轉帳已存入", threadName, ethLog.getBlockNumber());
            } catch (Exception e) {
                log.error("【解析失敗】執行緒 {} 發生錯誤: {}", threadName, e.getMessage());
            }
        }
    }
}
