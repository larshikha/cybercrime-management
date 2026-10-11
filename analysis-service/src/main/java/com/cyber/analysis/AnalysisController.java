package com.cyber.analysis;

import com.cyber.analysis.Dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class AnalysisController {

    private final ClassifierService classifier;
    private final PriorityService priority;
    private final RelatedCaseService related;

    public AnalysisController(ClassifierService classifier, PriorityService priority, RelatedCaseService related) {
        this.classifier = classifier;
        this.priority = priority;
        this.related = related;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "analysis-service", "status", "UP");
    }

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest req) {
        ComplaintDto c = req.complaint();
        List<ComplaintDto> history = req.history() == null ? List.of() : req.history();

        var cls = classifier.classify(c, history);
        // use predicted category for relation scoring
        ComplaintDto withCat = new ComplaintDto(c.id(), c.complaintNo(), cls.category(), c.description(), c.victimName(),
                c.victimProfile(), c.incidentDate(), c.amountLost(), c.suspectUpi(), c.suspectPhone(), c.suspectEmail(),
                c.suspectUrl(), c.suspectAccount(), c.suspectIp(), c.transactions());
        var rel = related.findRelated(withCat, history);
        var pri = priority.score(withCat, cls.category(), rel);
        var patterns = related.patterns(withCat, history);

        StringBuilder sb = new StringBuilder();
        sb.append("Classified as ").append(cls.category()).append(" (").append(Math.round(cls.confidence() * 100)).append("% confidence). ");
        sb.append("Priority ").append(pri.level()).append(" (score ").append(pri.score()).append("/100). ");
        if (rel.isEmpty()) sb.append("No strongly related earlier complaints found.");
        else sb.append(rel.size()).append(" potentially related earlier complaint(s); strongest match ")
                .append(rel.get(0).complaintNo()).append(" at ").append(Math.round(rel.get(0).score() * 100)).append("% similarity.");

        return new AnalyzeResponse(cls.category(), cls.confidence(), cls.scores(), cls.keywords(),
                pri.level(), pri.score(), pri.factors(), rel, patterns, sb.toString());
    }
}
