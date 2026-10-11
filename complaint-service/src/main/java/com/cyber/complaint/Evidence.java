package com.cyber.complaint;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "evidence")
public class Evidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long complaintId;
    public String evidenceType;      // URL / EMAIL / TRANSACTION / DOCUMENT / SCREENSHOT / OTHER
    public String refValue;
    @Column(length = 1000)
    public String note;
    public LocalDateTime addedAt;
}
