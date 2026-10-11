package com.cyber.complaint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Loads the historical (simulated) cybercrime complaint dataset from CSV on first start. */
@Component
public class DataLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final ComplaintRepository complaints;
    private final TxnRepository txns;
    private final EvidenceRepository evidence;
    private final InvestigationCaseRepository cases;
    private final CaseActivityRepository activities;
    private final MonitoringService monitoring;

    public DataLoader(ComplaintRepository complaints, TxnRepository txns, EvidenceRepository evidence,
                      InvestigationCaseRepository cases, CaseActivityRepository activities, MonitoringService monitoring) {
        this.complaints = complaints;
        this.txns = txns;
        this.evidence = evidence;
        this.cases = cases;
        this.activities = activities;
        this.monitoring = monitoring;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (complaints.count() > 0) return;
        int n = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/historical_complaints.csv").getInputStream(), StandardCharsets.UTF_8))) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                load(parse(line));
                n++;
            }
        }
        log.info("Loaded {} historical complaints from CSV", n);
        monitoring.refreshAlerts();
    }

    private void load(List<String> r) {
        Complaint c = new Complaint();
        c.complaintNo = r.get(0);
        c.category = r.get(1);
        c.description = r.get(2);
        c.victimName = r.get(3);
        c.victimProfile = r.get(4);
        c.incidentDate = LocalDate.parse(r.get(5));
        c.amountLost = Double.parseDouble(r.get(6));
        c.status = r.get(7);
        c.priority = r.get(8);
        c.priorityScore = switch (c.priority) { case "CRITICAL" -> 80; case "HIGH" -> 60; case "MEDIUM" -> 40; default -> 20; };
        c.suspectUpi = nz(r.get(9));
        c.suspectPhone = nz(r.get(10));
        c.suspectEmail = nz(r.get(11));
        c.suspectUrl = nz(r.get(12));
        c.suspectAccount = nz(r.get(13));
        c.suspectIp = nz(r.get(14));
        c.reportedAt = c.incidentDate.plusDays(1).atTime(10, 0);
        c.source = "SEED";
        c.assignedTo = nz(r.get(17));
        c = complaints.save(c);

        if (!r.get(15).isBlank()) {
            Txn t = new Txn();
            t.complaintId = c.id;
            t.txnRef = r.get(15);
            t.amount = c.amountLost;
            t.toAccount = c.suspectAccount;
            t.paymentMode = nz(r.get(16));
            t.txnDate = c.incidentDate;
            txns.save(t);
            ev(c.id, "TRANSACTION", r.get(15), "Reported transaction reference");
        }
        if (c.suspectUrl != null) ev(c.id, "URL", c.suspectUrl, "Reported malicious URL");
        if (c.suspectEmail != null) ev(c.id, "EMAIL", c.suspectEmail, "Suspect email address from complaint");

        if (c.assignedTo != null) {
            InvestigationCase ic = new InvestigationCase();
            ic.complaintId = c.id;
            ic.investigator = c.assignedTo;
            ic.caseStatus = switch (c.status) { case "CLOSED" -> "CLOSED"; case "ESCALATED" -> "ESCALATED"; default -> "IN_PROGRESS"; };
            ic.openedAt = c.reportedAt.plusDays(1);
            ic = cases.save(ic);
            ic.caseNo = String.format("CASE-%04d", ic.id);
            cases.save(ic);
            CaseActivity a = new CaseActivity();
            a.caseId = ic.id;
            a.activityType = "SYSTEM";
            a.note = "Case opened and assigned to " + ic.investigator + " (historical record).";
            a.createdBy = "system";
            a.createdAt = ic.openedAt;
            activities.save(a);
        }
    }

    private void ev(Long cid, String type, String ref, String note) {
        Evidence e = new Evidence();
        e.complaintId = cid;
        e.evidenceType = type;
        e.refValue = ref;
        e.note = note;
        e.addedAt = java.time.LocalDateTime.now();
        evidence.save(e);
    }

    private static String nz(String s) { return s == null || s.isBlank() ? null : s; }

    static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = false;
                } else sb.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(ch);
        }
        out.add(sb.toString());
        return out;
    }
}
