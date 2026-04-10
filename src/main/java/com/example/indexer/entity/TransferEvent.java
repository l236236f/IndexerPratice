package com.example.indexer.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Entity
@Table(name = "transfer_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 交易的唯一識別碼 (可在 Etherscan 查到)
    // 加上 unique = true 確保「冪等性」，即便重複掃描也不會重複入帳
    @Column(nullable = false, unique = true)
    private String transactionHash;

    // 區塊雜湊，用來檢查分岔 (重要的安全指紋)
    @Column(nullable = false)
    private String blockHash;

    // 該筆交易發生的區塊號碼
    @Column(nullable = false)
    private BigInteger blockNumber;

    // 發送代幣的錢包地址
    @Column(nullable = false)
    private String fromAddress;

    // 接收代幣的錢包地址
    @Column(nullable = false)
    private String toAddress;

    // 轉帳金額 (原始數值，未處理小位數)
    // 使用 precision=78 是因為乙太坊 uint256 最大可達 78 位十進位
    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger amount;

    // 這是哪一種代幣 (例如 USDT 的合約地址)
    @Column(nullable = false)
    private String contractAddress;

    // 索引器抓到這筆資料的本地時間
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
