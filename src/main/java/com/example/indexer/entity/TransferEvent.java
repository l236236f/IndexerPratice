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

    @Column(nullable = false)
    private String transactionHash;

    @Column(nullable = false)
    private BigInteger blockNumber;

    @Column(nullable = false)
    private String fromAddress;

    @Column(nullable = false)
    private String toAddress;

    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger amount;

    @Column(nullable = false)
    private String contractAddress;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
