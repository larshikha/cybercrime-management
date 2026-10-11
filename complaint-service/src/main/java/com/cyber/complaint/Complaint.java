package com.cyber.complaint;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "complaints")
public class Complaint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String complaintNo;
    public String category;          // officer-supplied or AI-assigned
    public String aiCategory;
    public Double aiConfidence;
    public String priority;          // LOW / MEDIUM / HIGH / CRITICAL
    public Integer priorityScore;
    public String status;            // REGISTERED / UNDER_INVESTIGATION / ESCALATED / CLOSED
    @Column(length = 4000)
    public String description;
    public String victimName;
    public String victimProfile;
    public String victimContact;
    public LocalDate incidentDate;
    public LocalDateTime reportedAt;
    public double amountLost;
    public String suspectUpi;
    public String suspectPhone;
    public String suspectEmail;
    public String suspectUrl;
    public String suspectAccount;
    public String suspectIp;
    public String assignedTo;
    public String source;            // SEED or WEB
    @Lob
    public String analysisJson;      // last AI analysis result (JSON)
}
