package com.cyber.complaint;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** Fraud / transaction monitoring, alert refresh and dashboard aggregation. */
@Service
public class MonitoringService {

    private final ComplaintRepository complaints;
    private final TxnRepository txns;
    private final InvestigationCaseRepository cases;
    private final CaseActivityRepository activities;
    private final AlertRepository alerts;
    private final ComplaintService svc;

    public MonitoringService(ComplaintRepository complaints, TxnRepository txns, InvestigationCaseRepository cases,
                             CaseActivityRepository activities, AlertRepository alerts, ComplaintService svc) {
        this.complaints = complaints;
        this.txns = txns;
        this.cases = cases;
        this.activities = activities;
        this.alerts = alerts;
        this.svc = svc;
    }

    static String clean(String s) { return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", ""); }

    static String domain(String url) {
        if (url == null || url.isBlank()) return "";
        String u = url.trim().toLowerCase();
        if (!u.contains("://")) u = "http://" + u;
        try {
            String h = URI.create(u).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    private void idx(Map<String, Set<Long>> m, String type, String value, Long id) {
        String v = clean(value);
        if (!v.isEmpty()) m.computeIfAbsent(type + "|" + v, k -> new LinkedHashSet<>()).add(id);
    }

    /** Identifiers (UPI, beneficiary account, phone, domain, email, IP) shared by 2+ complaints. */
    public List<Map<String, Object>> clusters() {
        List<Complaint> all = complaints.findAll();
        Map<Long, Complaint> byId = all.stream().collect(Collectors.toMap(c -> c.id, c -> c));
        Map<String, Set<Long>> idx = new LinkedHashMap<>();
        for (Complaint c : all) {
            idx(idx, "UPI_ID", c.suspectUpi, c.id);
            idx(idx, "PHONE", c.suspectPhone, c.id);
            idx(idx, "EMAIL", c.suspectEmail, c.id);
            idx(idx, "DOMAIN", domain(c.suspectUrl), c.id);
            idx(idx, "IP_ADDRESS", c.suspectIp, c.id);
            idx(idx, "BENEFICIARY_ACCOUNT", c.suspectAccount, c.id);
        }
        for (Txn t : txns.findAll()) idx(idx, "BENEFICIARY_ACCOUNT", t.toAccount, t.complaintId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : idx.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String[] kv = e.getKey().split("\\|", 2);
            List<Complaint> cs = e.getValue().stream().map(byId::get).filter(Objects::nonNull).collect(Collectors.toList());
            double loss = cs.stream().mapToDouble(c -> c.amountLost).sum();
            LocalDate first = cs.stream().map(c -> c.incidentDate).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            LocalDate last = cs.stream().map(c -> c.incidentDate).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            int n = cs.size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", kv[0]);
            m.put("value", kv[1]);
            m.put("complaintCount", n);
            m.put("totalLoss", loss);
            m.put("complaintNos", cs.stream().map(c -> c.complaintNo).collect(Collectors.toList()));
            m.put("latestComplaintId", cs.stream().mapToLong(c -> c.id).max().orElse(0));
            m.put("firstSeen", first);
            m.put("lastSeen", last);
            m.put("risk", n >= 5 || loss >= 500000 ? "HIGH" : n >= 3 ? "MEDIUM" : "LOW");
            out.add(m);
        }
        out.sort((a, b) -> {
            int c = Integer.compare((Integer) b.get("complaintCount"), (Integer) a.get("complaintCount"));
            return c != 0 ? c : Double.compare((Double) b.get("totalLoss"), (Double) a.get("totalLoss"));
        });
        return out;
    }

    /** Alert generation for recurring patterns and stalled / unassigned high-priority work. */
    public void refreshAlerts() {
        for (Map<String, Object> cl : clusters()) {
            if ((Integer) cl.get("complaintCount") < 3) continue;
            Complaint latest = complaints.findById((Long) cl.get("latestComplaintId")).orElse(null);
            if (latest == null) continue;
            svc.raise(latest, "RECURRING_PATTERN", "WARNING",
                    cl.get("type") + " '" + cl.get("value") + "' linked to " + cl.get("complaintCount")
                            + " complaints, total reported loss Rs " + String.format("%,.0f", (Double) cl.get("totalLoss")));
        }
        LocalDateTime now = LocalDateTime.now();
        for (Complaint c : complaints.findAll()) {
            boolean hi = "HIGH".equals(c.priority) || "CRITICAL".equals(c.priority);
            if (hi && "REGISTERED".equals(c.status) && c.reportedAt != null && c.reportedAt.isBefore(now.minusDays(2)) && c.reportedAt.isAfter(now.minusDays(30)))
                svc.raise(c, "PENDING_ACTIVITY", "WARNING", c.priority + " priority complaint has not been assigned for over 2 days");
        }
        for (InvestigationCase ic : cases.findAll()) {
            if ("CLOSED".equals(ic.caseStatus)) continue;
            Complaint c = complaints.findById(ic.complaintId).orElse(null);
            if (c == null || !("HIGH".equals(c.priority) || "CRITICAL".equals(c.priority))) continue;
            LocalDateTime last = activities.findByCaseIdOrderByCreatedAtDesc(ic.id).stream()
                    .map(a -> a.createdAt).findFirst().orElse(ic.openedAt);
            long idle = last == null ? 0 : ChronoUnit.DAYS.between(last, now);
            if (idle >= 5 && idle <= 30)
                svc.raise(c, "PENDING_ACTIVITY", "INFO", "No investigation activity on " + ic.caseNo + " for " + idle
                        + " days (investigator: " + ic.investigator + ")");
        }
    }

    public List<Alert> openAlerts() {
        refreshAlerts();
        List<Alert> l = alerts.findByResolvedFalseOrderByCreatedAtDesc();
        Map<String, Integer> rank = Map.of("CRITICAL", 0, "WARNING", 1, "INFO", 2);
        l.sort(Comparator.comparing((Alert a) -> rank.getOrDefault(a.alertLevel, 3)).thenComparing((Alert a) -> a.createdAt, Comparator.reverseOrder()));
        return l;
    }

    public Map<String, Object> dashboard() {
        List<Complaint> all = complaints.findAll();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("totalComplaints", all.size());
        d.put("totalLoss", all.stream().mapToDouble(c -> c.amountLost).sum());
        d.put("criticalHigh", all.stream().filter(c -> "CRITICAL".equals(c.priority) || "HIGH".equals(c.priority)).count());
        List<InvestigationCase> cs = cases.findAll();
        d.put("openCases", cs.stream().filter(c -> !"CLOSED".equals(c.caseStatus)).count());
        d.put("openAlerts", openAlerts().size());

        d.put("byCategory", count(all, c -> c.category == null ? "UNCLASSIFIED" : c.category));
        d.put("byPriority", count(all, c -> c.priority == null ? "LOW" : c.priority));
        d.put("byStatus", count(all, c -> c.status));
        Map<String, Double> loss = new TreeMap<>();
        for (Complaint c : all) loss.merge(c.category == null ? "UNCLASSIFIED" : c.category, c.amountLost, Double::sum);
        d.put("lossByCategory", loss);

        Map<String, Map<String, Object>> monthly = new TreeMap<>();
        for (Complaint c : all) {
            if (c.incidentDate == null) continue;
            String k = c.incidentDate.getYear() + "-" + String.format("%02d", c.incidentDate.getMonthValue());
            Map<String, Object> m = monthly.computeIfAbsent(k, x -> new LinkedHashMap<>(Map.of("count", 0, "loss", 0.0)));
            m.put("count", (Integer) m.get("count") + 1);
            m.put("loss", (Double) m.get("loss") + c.amountLost);
        }
        d.put("monthly", monthly);
        d.put("caseProgress", cs.stream().collect(Collectors.groupingBy(c -> c.caseStatus, TreeMap::new, Collectors.counting())));
        List<Map<String, Object>> cl = clusters();
        d.put("topClusters", cl.size() > 6 ? cl.subList(0, 6) : cl);
        d.put("recentHighPriority", all.stream().filter(c -> "CRITICAL".equals(c.priority) || "HIGH".equals(c.priority))
                .sorted(Comparator.comparing((Complaint c) -> c.id).reversed()).limit(6)
                .map(svc::summary).collect(Collectors.toList()));
        return d;
    }

    private Map<String, Long> count(List<Complaint> all, java.util.function.Function<Complaint, String> f) {
        return all.stream().collect(Collectors.groupingBy(f, TreeMap::new, Collectors.counting()));
    }
}
