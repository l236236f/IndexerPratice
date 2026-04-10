package com.example.indexer.repository;

import com.example.indexer.entity.TransferEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransferEventRepository extends JpaRepository<TransferEvent, Long> {
}
