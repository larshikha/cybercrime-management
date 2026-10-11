package com.cyber.complaint;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "case_activities")
public class CaseActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long caseId;
    public String activityType;      // OBSERVATION / BANK_REQUEST / PLATFORM_NOTICE / INTERVIEW / EVIDENCE / SYSTEM ...
    @Column(length = 2000)
    public String note;
    public String evidenceRef;
    public String createdBy;
    public LocalDateTime createdAt;
}
