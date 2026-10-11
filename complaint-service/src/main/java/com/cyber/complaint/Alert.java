package com.cyber.complaint;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alerts")
public class Alert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long complaintId;
    public String complaintNo;
    public String alertLevel;        // INFO / WARNING / CRITICAL
    public String alertType;         // HIGH_PRIORITY / RELATED_CASES / RECURRING_PATTERN / PENDING_ACTIVITY / ESCALATION
    @Column(length = 1000)
    public String message;
    public LocalDateTime createdAt;
    public boolean resolved;
}
