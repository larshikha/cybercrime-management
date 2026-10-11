package com.cyber.complaint;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TxnRepository extends JpaRepository<Txn, Long> {
    List<Txn> findByComplaintId(Long complaintId);
}
