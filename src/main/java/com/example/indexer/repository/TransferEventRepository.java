package com.example.indexer.repository;

import com.example.indexer.entity.TransferEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Optional;

@Repository
public interface TransferEventRepository extends JpaRepository<TransferEvent, Long> {
    
    /**
     * 尋找資料庫中區塊高度最高的一筆記錄
     * 用來輔助檢測分岔點
     */
    Optional<TransferEvent> findFirstByOrderByBlockNumberDesc();

    /**
     * 刪除所有大於等於指定高度的區塊資料
     * 當偵測到分岔 (Re-org) 時，我們需要回溯並清除這些不穩定的資料
     */
    @Transactional
    void deleteByBlockNumberGreaterThanEqual(BigInteger blockNumber);
}
