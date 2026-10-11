package com.cyber.complaint;

import com.cyber.complaint.Requests.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ComplaintService {

    private final ComplaintRepository complaints;
    private final TxnRepository txns;
    private final EvidenceRepository evidence;
    private final InvestigationCaseRepository cases;
    private final CaseActivityRepository activities;
    private final AlertRepository alerts;
    private final AnalysisClient client;
    private final ObjectMapper mapper;

    public ComplaintService(ComplaintRepository complaints, TxnRepository txns, EvidenceRepository evidence,
                            InvestigationCaseRepository cases, CaseActivityRepository activities,
                            AlertRepository alerts, AnalysisClient client, ObjectMapper mapper) {
        this.complaints = complaints;
        this.txns = txns;
        this.evidence = evidence;
        this.cases = cases;
        this.activities = activities;
        this.alerts = alerts;
        this.client = client;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ helpers
    public Complaint get(Long id) {
        return complaints.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint " + id + " not found"));
    }

    static boolean has(String s) { return s != null && !s.isBlank(); }
    static String nz(String s) { return has(s) ? s.trim() : null; }

    Map<String, Object> toAnalysisMap(Complaint c, List<Txn> t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("complaintNo", c.complaintNo);
        m.put("category", c.category);
        m.put("description", c.description);
        m.put("victimName", c.victimName);
        m.put("victimProfile", c.victimProfile);
        m.put("incidentDate", c.incidentDate == null ? null : c.incidentDate.toString());
        m.put("amountLost", c.amountLost);
        m.put("suspectUpi", c.suspectUpi);
        m.put("suspectPhone", c.suspectPhone);
        m.put("suspectEmail", c.suspectEmail);
        m.put("suspectUrl", c.suspectUrl);
        m.put("suspectAccount", c.suspectAccount);
        m.put("suspectIp", c.suspectIp);
        List<Map<String, Object>> tl = new ArrayList<>();
        for (Txn x : t) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("txnRef", x.txnRef);
            tm.put("amount", x.amount);
            tm.put("fromAccount", x.fromAccount);
            tm.put("toAccount", x.toAccount);
            tm.put("mode", x.paymentMode);
            tl.add(tm);
        }
        m.put("transactions", tl);
        return m;
    }

    // ------------------------------------------------------------------ registration + AI
    public Complaint register(ComplaintRequest r) {
        if (!has(r.description())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required");
        Complaint c = new Complaint();
        c.category = nz(r.category());
        c.description = r.description().trim();
        c.victimName = nz(r.victimName());
        c.victimProfile = nz(r.victimProfile());
        c.victimContact = nz(r.victimContact());
        c.incidentDate = has(r.incidentDate()) ? LocalDate.parse(r.incidentDate()) : LocalDate.now();
        c.amountLost = r.amountLost() == null ? 0 : r.amountLost();
        c.suspectUpi = nz(r.suspectUpi());
        c.suspectPhone = nz(r.suspectPhone());
        c.suspectEmail = nz(r.suspectEmail());
        c.suspectUrl = nz(r.suspectUrl());
        c.suspectAccount = nz(r.suspectAccount());
        c.suspectIp = nz(r.suspectIp());
        c.status = "REGISTERED";
        c.priority = "LOW";
        c.priorityScore = 0;
        c.reportedAt = LocalDateTime.now();
        c.source = "WEB";
        c = complaints.save(c);
        c.complaintNo = String.format("CC-%d-%04d", c.reportedAt.getYear(), c.id);
        c = complaints.save(c);

        if (c.amountLost > 0 || has(r.txnRef()) || has(r.toAccount())) {
            Txn t = new Txn();
            t.complaintId = c.id;
            t.txnRef = has(r.txnRef()) ? r.txnRef().trim() : "TXN-" + c.id;
            t.amount = c.amountLost;
            t.fromAccount = nz(r.fromAccount());
            t.toAccount = has(r.toAccount()) ? r.toAccount().trim() : c.suspectAccount;
            t.paymentMode = has(r.paymentMode()) ? r.paymentMode() : "UPI";
            t.txnDate = c.incidentDate;
            txns.save(t);
        }
        analyze(c.id);
        return get(c.id);
    }

    /** Calls analysis-service with this complaint + all historical complaints; stores the result. */
    public boolean analyze(Long id) {
        Complaint c = get(id);
        Map<Long, List<Txn>> byC = txns.findAll().stream().collect(Collectors.groupingBy(t -> t.complaintId));
        List<Map<String, Object>> history = new ArrayList<>();
        for (Complaint h : complaints.findAll())
            if (!h.id.equals(id)) history.add(toAnalysisMap(h, byC.getOrDefault(h.id, List.of())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("complaint", toAnalysisMap(c, byC.getOrDefault(id, List.of())));
        body.put("history", history);
        JsonNode res = client.analyze(body);
        if (res == null) return false;

        c.aiCategory = res.path("category").asText(null);
        c.aiConfidence = res.path("confidence").asDouble();
        if (!has(c.category)) c.category = c.aiCategory;
        c.priority = res.path("priority").asText("LOW");
        c.priorityScore = res.path("priorityScore").asInt();
        c.analysisJson = res.toString();
        complaints.save(c);
        raiseAnalysisAlerts(c, res);
        return true;
    }

    private void raiseAnalysisAlerts(Complaint c, JsonNode res) {
        String pr = c.priority;
        if ("CRITICAL".equals(pr) || "HIGH".equals(pr)) {
            raise(c, "HIGH_PRIORITY", "CRITICAL".equals(pr) ? "CRITICAL" : "WARNING",
                    "AI priority " + pr + " (score " + c.priorityScore + "/100) - " + c.category
                            + (c.amountLost > 0 ? ", reported loss Rs " + String.format("%,.0f", c.amountLost) : ""));
        }
        List<String> strong = new ArrayList<>();
        for (JsonNode r : res.path("related")) if (r.path("score").asDouble() >= 0.5) strong.add(r.path("complaintNo").asText());
        if (!strong.isEmpty())
            raise(c, "RELATED_CASES", "WARNING", "Linked to " + strong.size() + " earlier complaint(s): " + String.join(", ", strong));
        JsonNode patterns = res.path("patterns");
        if (patterns.isArray() && patterns.size() > 0)
            raise(c, "RECURRING_PATTERN", "WARNING", patterns.get(0).asText());
    }

    public Alert raise(Complaint c, String type, String level, String message) {
        boolean dup = alerts.findByResolvedFalseOrderByCreatedAtDesc().stream()
                .anyMatch(a -> Objects.equals(a.complaintId, c.id) && type.equals(a.alertType) && message.equals(a.message));
        if (dup) return null;
        Alert a = new Alert();
        a.complaintId = c.id;
        a.complaintNo = c.complaintNo;
        a.alertLevel = level;
        a.alertType = type;
        a.message = message;
        a.createdAt = LocalDateTime.now();
        return alerts.save(a);
    }

    // ------------------------------------------------------------------ search / detail
    public List<Map<String, Object>> search(String q, String category, String status, String priority) {
        String needle = has(q) ? q.trim().toLowerCase() : null;
        return complaints.findAll().stream()
                .filter(c -> !has(category) || category.equals(c.category))
                .filter(c -> !has(status) || status.equals(c.status))
                .filter(c -> !has(priority) || priority.equals(c.priority))
                .filter(c -> needle == null || matches(c, needle))
                .sorted(Comparator.comparing((Complaint c) -> c.id).reversed())
                .limit(200)
                .map(this::summary)
                .collect(Collectors.toList());
    }

    private boolean matches(Complaint c, String n) {
        for (String f : new String[]{c.complaintNo, c.description, c.victimName, c.suspectUpi, c.suspectPhone,
                c.suspectEmail, c.suspectUrl, c.suspectAccount, c.suspectIp, c.category})
            if (f != null && f.toLowerCase().contains(n)) return true;
        return false;
    }

    Map<String, Object> summary(Complaint c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("complaintNo", c.complaintNo);
        m.put("category", c.category);
        m.put("priority", c.priority);
        m.put("priorityScore", c.priorityScore);
        m.put("status", c.status);
        m.put("victimName", c.victimName);
        m.put("incidentDate", c.incidentDate);
        m.put("amountLost", c.amountLost);
        m.put("assignedTo", c.assignedTo);
        return m;
    }

    public Map<String, Object> detail(Long id) {
        Complaint c = get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        JsonNode analysis = null;
        try { if (c.analysisJson != null) analysis = mapper.readTree(c.analysisJson); } catch (Exception ignored) { }
        Complaint copy = c;
        m.put("complaint", copy);
        m.put("transactions", txns.findByComplaintId(id));
        m.put("evidence", evidence.findByComplaintId(id));
        List<InvestigationCase> cs = cases.findByComplaintId(id);
        InvestigationCase ic = cs.isEmpty() ? null : cs.get(0);
        m.put("case", ic);
        m.put("activities", ic == null ? List.of() : activities.findByCaseIdOrderByCreatedAtDesc(ic.id));
        m.put("analysis", analysis);
        m.put("recommendedActions", recommendedActions(c, analysis));
        return m;
    }

    List<String> recommendedActions(Complaint c, JsonNode a) {
        List<String> acts = new ArrayList<>();
        if (c.amountLost > 0 && c.incidentDate != null) {
            long days = ChronoUnit.DAYS.between(c.incidentDate, LocalDate.now());
            if (days <= 1)
                acts.add("URGENT (golden hour): raise a hold/freeze request with the beneficiary bank/UPI provider and log it on the National Cyber Crime Reporting Portal / helpline 1930.");
            else
                acts.add("Request beneficiary KYC, account statement and any funds still on hold from the bank / UPI provider.");
        }
        if (a != null) {
            List<String> strong = new ArrayList<>();
            for (JsonNode r : a.path("related")) if (r.path("score").asDouble() >= 0.5) strong.add(r.path("complaintNo").asText());
            if (!strong.isEmpty())
                acts.add("Consolidate with linked complaint(s) " + String.join(", ", strong) + " under one lead case and assign a single investigator.");
            if (a.path("patterns").size() > 0)
                acts.add("Seek subscriber / account-holder details for the recurring identifier(s) listed under Patterns.");
        }
        String cat = c.category == null ? "" : c.category;
        switch (cat) {
            case "PHISHING" -> acts.add("Request takedown/blocking of the phishing domain via the host and CERT-In; preserve screenshots and the URL as evidence.");
            case "SOCIAL_MEDIA_ABUSE" -> acts.add("Send a preservation + takedown request to the platform for the offending profile; collect URLs and screenshots; advise victim on privacy settings.");
            case "IDENTITY_THEFT" -> acts.add("Alert the telecom operator and credit bureaus; request the list of SIMs/accounts issued against the victim's ID.");
            case "ACCOUNT_COMPROMISE" -> acts.add("Request login/IP logs from the service provider; advise the victim to reset credentials and revoke active sessions.");
            case "ONLINE_FINANCIAL_FRAUD" -> acts.add("Trace the full transaction trail and request payment-provider logs for every hop.");
            default -> acts.add("Manual review required: AI could not confidently assign a category.");
        }
        String prof = c.victimProfile == null ? "" : c.victimProfile.toLowerCase();
        if (prof.contains("senior") || prof.contains("pension") || prof.contains("minor") || prof.contains("student"))
            acts.add("Vulnerable victim: assign a senior officer and arrange victim support contact.");
        return acts;
    }

    public Map<String, Object> newFraudLinks(int days) {
        LocalDate since = LocalDate.now().minusDays(days);
        List<Complaint> recent = complaints.findAll().stream()
                .filter(c -> c.incidentDate != null && !c.incidentDate.isBefore(since))
                .filter(c -> "ONLINE_FINANCIAL_FRAUD".equals(c.category) || "ONLINE_FINANCIAL_FRAUD".equals(c.aiCategory))
                .sorted(Comparator.comparing((Complaint c) -> c.id).reversed()).collect(Collectors.toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        int linked = 0;
        for (Complaint c : recent) {
            if (c.analysisJson == null) analyze(c.id);
            Complaint fresh = get(c.id);
            JsonNode a = null;
            try { if (fresh.analysisJson != null) a = mapper.readTree(fresh.analysisJson); } catch (Exception ignored) { }
            List<Map<String, Object>> strong = new ArrayList<>();
            if (a != null)
                for (JsonNode r : a.path("related"))
                    if (r.path("score").asDouble() >= 0.5) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("complaintNo", r.path("complaintNo").asText());
                        x.put("score", r.path("score").asDouble());
                        List<String> fs = new ArrayList<>();
                        for (JsonNode f : r.path("factors")) fs.add(f.asText());
                        x.put("factors", fs);
                        strong.add(x);
                    }
            if (!strong.isEmpty()) linked++;
            Map<String, Object> row = new LinkedHashMap<>(summary(fresh));
            row.put("verdict", strong.isEmpty() ? "NO STRONG LINK" : "LINKED");
            row.put("linkedCases", strong);
            row.put("topPattern", a != null && a.path("patterns").size() > 0 ? a.path("patterns").get(0).asText() : null);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", "Are these newly reported online financial-fraud complaints related to any previously reported cases?");
        out.put("windowDays", days);
        out.put("reviewed", recent.size());
        out.put("linked", linked);
        out.put("answer", recent.isEmpty() ? "No online financial-fraud complaints found in the last " + days + " days."
                : linked + " of " + recent.size() + " newly reported online financial-fraud complaints are linked to earlier cases through shared identifiers, beneficiary accounts or modus operandi.");
        out.put("complaints", rows);
        return out;
    }

    // ------------------------------------------------------------------ transactions / evidence
    public Txn addTxn(Long complaintId, TxnRequest r) {
        get(complaintId);
        Txn t = new Txn();
        t.complaintId = complaintId;
        t.txnRef = nz(r.txnRef());
        t.amount = r.amount() == null ? 0 : r.amount();
        t.fromAccount = nz(r.fromAccount());
        t.toAccount = nz(r.toAccount());
        t.paymentMode = has(r.paymentMode()) ? r.paymentMode() : "UPI";
        t.txnDate = has(r.txnDate()) ? LocalDate.parse(r.txnDate()) : LocalDate.now();
        return txns.save(t);
    }

    public Evidence addEvidence(Long complaintId, EvidenceRequest r) {
        get(complaintId);
        Evidence e = new Evidence();
        e.complaintId = complaintId;
        e.evidenceType = has(r.evidenceType()) ? r.evidenceType() : "OTHER";
        e.refValue = nz(r.refValue());
        e.note = nz(r.note());
        e.addedAt = LocalDateTime.now();
        return evidence.save(e);
    }

    // ------------------------------------------------------------------ cases
    public InvestigationCase openCase(Long complaintId, String investigator) {
        Complaint c = get(complaintId);
        List<InvestigationCase> existing = cases.findByComplaintId(complaintId);
        if (!existing.isEmpty()) return existing.get(0);
        InvestigationCase ic = new InvestigationCase();
        ic.complaintId = complaintId;
        ic.investigator = has(investigator) ? investigator.trim() : "Unassigned";
        ic.caseStatus = "OPEN";
        ic.openedAt = LocalDateTime.now();
        ic = cases.save(ic);
        ic.caseNo = String.format("CASE-%04d", ic.id);
        ic = cases.save(ic);
        c.status = "UNDER_INVESTIGATION";
        c.assignedTo = ic.investigator;
        complaints.save(c);

        String summary = "Case opened and assigned to " + ic.investigator + ".";
        try {
            if (c.analysisJson != null) summary += " AI summary: " + mapper.readTree(c.analysisJson).path("summary").asText();
        } catch (Exception ignored) { }
        activity(ic.id, "SYSTEM", summary, null, "system");
        return ic;
    }

    public CaseActivity activity(Long caseId, String type, String note, String evidenceRef, String by) {
        CaseActivity a = new CaseActivity();
        a.caseId = caseId;
        a.activityType = has(type) ? type : "OBSERVATION";
        a.note = note;
        a.evidenceRef = nz(evidenceRef);
        a.createdBy = has(by) ? by : "officer";
        a.createdAt = LocalDateTime.now();
        return activities.save(a);
    }

    public CaseActivity addActivity(Long caseId, ActivityRequest r) {
        InvestigationCase ic = cases.findById(caseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        if ("OPEN".equals(ic.caseStatus)) {
            ic.caseStatus = "IN_PROGRESS";
            cases.save(ic);
        }
        return activity(caseId, r.activityType(), r.note(), r.evidenceRef(), r.createdBy());
    }

    public InvestigationCase updateCaseStatus(Long caseId, String status) {
        InvestigationCase ic = cases.findById(caseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        Set<String> ok = Set.of("OPEN", "IN_PROGRESS", "ESCALATED", "CLOSED");
        if (!ok.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
        ic.caseStatus = status;
        cases.save(ic);
        Complaint c = get(ic.complaintId);
        switch (status) {
            case "ESCALATED" -> {
                c.status = "ESCALATED";
                raise(c, "ESCALATION", "CRITICAL", "Case " + ic.caseNo + " escalated by " + ic.investigator + " for senior review");
            }
            case "CLOSED" -> c.status = "CLOSED";
            default -> c.status = "UNDER_INVESTIGATION";
        }
        complaints.save(c);
        activity(caseId, "SYSTEM", "Case status changed to " + status, null, "system");
        return ic;
    }

    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvestigationCase ic : cases.findAll()) {
            Complaint c = complaints.findById(ic.complaintId).orElse(null);
            if (c == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ic.id);
            m.put("caseNo", ic.caseNo);
            m.put("complaintId", c.id);
            m.put("complaintNo", c.complaintNo);
            m.put("category", c.category);
            m.put("priority", c.priority);
            m.put("investigator", ic.investigator);
            m.put("caseStatus", ic.caseStatus);
            m.put("openedAt", ic.openedAt);
            out.add(m);
        }
        out.sort((x, y) -> Long.compare((Long) y.get("id"), (Long) x.get("id")));
        return out;
    }
}
