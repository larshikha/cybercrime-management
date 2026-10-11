package com.cyber.analysis;

import com.cyber.analysis.Dto.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Transparent additive risk-scoring model; every point awarded is returned as a human-readable factor. */
@Service
public class PriorityService {

    public record Result(String level, int score, List<String> factors) {}

    public Result score(ComplaintDto c, String category, List<RelatedCase> related) {
        int score = 0;
        List<String> f = new ArrayList<>();
        double amt = c.amountLost();

        int a = amt >= 1_000_000 ? 40 : amt >= 500_000 ? 32 : amt >= 100_000 ? 24 : amt >= 25_000 ? 14 : amt >= 5_000 ? 8 : amt > 0 ? 3 : 0;
        if (a > 0) { score += a; f.add("+" + a + "  Financial loss of Rs " + String.format("%,.0f", amt)); }

        int cw = switch (category) {
            case "ONLINE_FINANCIAL_FRAUD" -> 20;
            case "IDENTITY_THEFT" -> 18;
            case "SOCIAL_MEDIA_ABUSE" -> 16;
            case "ACCOUNT_COMPROMISE" -> 15;
            case "PHISHING" -> 14;
            default -> 5;
        };
        score += cw;
        f.add("+" + cw + "  Crime category severity weight (" + category + ")");

        String text = Text.normalize(c.description() + " " + (c.victimProfile() == null ? "" : c.victimProfile()));
        if (hasAny(text, "senior citizen", "pensioner", "pension", "minor", "child", "school student", "elderly", "widow")) {
            score += 10;
            f.add("+10  Vulnerable victim indicators (senior citizen / minor)");
        }
        if (hasAny(text, "blackmail", "threat", "morph", "sextortion", "viral", "ongoing", "still being", "demanding more", "again")) {
            score += 10;
            f.add("+10  Ongoing harm / coercion indicators in narrative");
        }

        LocalDate d = RelatedCaseService.parse(c.incidentDate());
        if (d != null && amt > 0) {
            long days = ChronoUnit.DAYS.between(d, LocalDate.now());
            if (days >= 0 && days <= 1) {
                score += 10;
                f.add("+10  Golden-hour window: reported within 24h, funds may still be freezable");
            }
        }

        long strong = related.stream().filter(r -> r.score() >= 0.5).count();
        if (strong > 0) {
            int pts = (int) Math.min(24, strong * 8);
            score += pts;
            f.add("+" + pts + "  Linked to " + strong + " earlier complaint(s) via shared identifiers/accounts (organised-activity signal)");
        }

        score = Math.min(100, score);
        String level = score >= 70 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW";
        return new Result(level, score, f);
    }

    private static boolean hasAny(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }
}
