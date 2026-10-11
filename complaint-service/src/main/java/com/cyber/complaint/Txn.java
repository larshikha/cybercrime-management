package com.cyber.complaint;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "txns")
public class Txn {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long complaintId;
    public String txnRef;
    public double amount;
    public String fromAccount;
    public String toAccount;
    public String paymentMode;
    public LocalDate txnDate;
}
