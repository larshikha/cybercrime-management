package com.cyber.complaint;

import com.cyber.complaint.Requests.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ComplaintController {

    private final ComplaintService svc;
    private final MonitoringService mon;
    private final AlertRepository alerts;
    private final AnalysisClient analysisClient;

    public ComplaintController(ComplaintService svc, MonitoringService mon, AlertRepository alerts, AnalysisClient analysisClient) {
        this.svc = svc;
        this.mon = mon;
        this.alerts = alerts;
        this.analysisClient = analysisClient;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("service", "complaint-service", "status", "UP", "analysisServiceUp", analysisClient.isUp());
    }

    // ---- complaints
    @GetMapping("/complaints")
    public List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String status, @RequestParam(required = false) String priority) {
        return svc.search(q, category, status, priority);
    }

    @GetMapping("/complaints/{id}")
    public Map<String, Object> detail(@PathVariable Long id) { return svc.detail(id); }

    @PostMapping("/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody ComplaintRequest r) {
        Complaint c = svc.register(r);
        Map<String, Object> d = svc.detail(c.id);
        d.put("analysisAvailable", c.analysisJson != null);
        return d;
    }

    @PostMapping("/complaints/{id}/analyze")
    public Map<String, Object> reanalyze(@PathVariable Long id) {
        boolean ok = svc.analyze(id);
        Map<String, Object> d = svc.detail(id);
        d.put("analysisAvailable", ok);
        return d;
    }

    @PostMapping("/complaints/{id}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public Txn addTxn(@PathVariable Long id, @RequestBody TxnRequest r) { return svc.addTxn(id, r); }

    @PostMapping("/complaints/{id}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public Evidence addEvidence(@PathVariable Long id, @RequestBody EvidenceRequest r) { return svc.addEvidence(id, r); }

    @PostMapping("/complaints/{id}/case")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestigationCase openCase(@PathVariable Long id, @RequestBody CaseRequest r) { return svc.openCase(id, r.investigator()); }

    // ---- cases
    @GetMapping("/cases")
    public List<Map<String, Object>> cases() { return svc.listCases(); }

    @PostMapping("/cases/{caseId}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseActivity addActivity(@PathVariable Long caseId, @RequestBody ActivityRequest r) { return svc.addActivity(caseId, r); }

    @PutMapping("/cases/{caseId}/status")
    public InvestigationCase caseStatus(@PathVariable Long caseId, @RequestBody StatusRequest r) { return svc.updateCaseStatus(caseId, r.status()); }

    // ---- alerts, monitoring, dashboard, insights
    @GetMapping("/alerts")
    public List<Alert> alerts() { return mon.openAlerts(); }

    @PostMapping("/alerts/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable Long id) {
        alerts.findById(id).ifPresent(a -> { a.resolved = true; alerts.save(a); });
        return Map.of("resolved", id);
    }

    @GetMapping("/monitoring/clusters")
    public List<Map<String, Object>> clusters() { return mon.clusters(); }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return mon.dashboard(); }

    /** The illustrative officer query from the problem statement. */
    @GetMapping("/insights/new-fraud-links")
    public Map<String, Object> newFraudLinks(@RequestParam(defaultValue = "7") int days) { return svc.newFraudLinks(days); }
}
