package com.cyber.complaint;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cases")
public class InvestigationCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String caseNo;
    public Long complaintId;
    public String investigator;
    public String caseStatus;        // OPEN / IN_PROGRESS / ESCALATED / CLOSED
    public LocalDateTime openedAt;
}
