package com.cyber.analysis;

import com.cyber.analysis.Dto.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Related-case identification: shared digital identifiers / beneficiary accounts (hard evidence)
 * combined with TF-IDF cosine similarity of narrative text (soft evidence).
 */
@Service
public class RelatedCaseService {

    public List<RelatedCase> findRelated(ComplaintDto c, List<ComplaintDto> history) {
        List<ComplaintDto> pool = new ArrayList<>();
        for (ComplaintDto h : history) if (!Objects.equals(h.id(), c.id())) pool.add(h);
        if (pool.isEmpty()) return List.of();

        // TF-IDF over corpus
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> docs = new ArrayList<>();
        List<String> qTokens = Text.tokens(c.description());
        docs.add(qTokens);
        for (ComplaintDto h : pool) docs.add(Text.tokens(h.description()));
        for (List<String> d : docs) for (String t : new HashSet<>(d)) df.merge(t, 1, Integer::sum);
        int n = docs.size();
        Map<String, Double> qVec = tfidf(qTokens, df, n);

        List<RelatedCase> out = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            ComplaintDto h = pool.get(i);
            List<String> factors = new ArrayList<>();
            double idScore = 0;

            if (same(c.suspectUpi(), h.suspectUpi())) { idScore += 0.45; factors.add("Same suspect UPI ID: " + c.suspectUpi()); }
            if (same(c.suspectPhone(), h.suspectPhone())) { idScore += 0.45; factors.add("Same suspect phone number: " + c.suspectPhone()); }
            if (same(c.suspectEmail(), h.suspectEmail())) { idScore += 0.40; factors.add("Same suspect email: " + c.suspectEmail()); }
            String d1 = Text.domain(c.suspectUrl()), d2 = Text.domain(h.suspectUrl());
            if (!d1.isEmpty() && d1.equals(d2)) { idScore += 0.40; factors.add("Same malicious domain: " + d1); }
            if (same(c.suspectIp(), h.suspectIp())) { idScore += 0.35; factors.add("Same suspect IP address: " + c.suspectIp()); }
            Set<String> mine = accounts(c), theirs = accounts(h);
            Set<String> common = new LinkedHashSet<>(mine);
            common.retainAll(theirs);
            if (!common.isEmpty()) { idScore += 0.45; factors.add("Money routed to same beneficiary account: " + String.join(", ", common)); }

            double cos = cosine(qVec, tfidf(docs.get(i + 1), df, n));
            if (cos >= 0.15) factors.add(String.format("Narrative similarity %.0f%% (common modus operandi wording)", cos * 100));

            double bonus = 0;
            if (c.category() != null && c.category().equals(h.category())) { bonus += 0.08; factors.add("Same crime category: " + c.category()); }
            long gap = daysBetween(c.incidentDate(), h.incidentDate());
            if (gap >= 0 && gap <= 30) { bonus += 0.04; factors.add("Incidents within " + gap + " days of each other"); }

            double score = Math.min(1.0, Math.min(idScore, 0.85) + 0.40 * cos + bonus);
            if (score >= 0.25) out.add(new RelatedCase(h.id(), h.complaintNo(), h.category(), Math.round(score * 1000) / 1000.0, factors));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    public List<String> patterns(ComplaintDto c, List<ComplaintDto> history) {
        List<String> p = new ArrayList<>();
        List<ComplaintDto> pool = history.stream().filter(h -> !Objects.equals(h.id(), c.id())).toList();
        identifierPattern(p, "UPI ID", c.suspectUpi(), pool, h -> h.suspectUpi());
        identifierPattern(p, "phone number", c.suspectPhone(), pool, h -> h.suspectPhone());
        identifierPattern(p, "email address", c.suspectEmail(), pool, h -> h.suspectEmail());
        identifierPattern(p, "IP address", c.suspectIp(), pool, h -> h.suspectIp());
        String dom = Text.domain(c.suspectUrl());
        if (!dom.isEmpty()) {
            List<ComplaintDto> m = pool.stream().filter(h -> dom.equals(Text.domain(h.suspectUrl()))).toList();
            if (!m.isEmpty()) p.add("Domain '" + dom + "' already reported in " + m.size() + " earlier complaint(s), total reported loss Rs " + fmt(sumLoss(m)));
        }
        for (String acct : accounts(c)) {
            List<ComplaintDto> m = pool.stream().filter(h -> accounts(h).contains(acct)).toList();
            if (!m.isEmpty()) p.add("Beneficiary account " + acct + " received money in " + m.size() + " earlier complaint(s), total reported loss Rs " + fmt(sumLoss(m)));
        }
        if (c.category() != null) {
            LocalDate ref = parse(c.incidentDate()) != null ? parse(c.incidentDate()) : LocalDate.now();
            long recent = pool.stream().filter(h -> c.category().equals(h.category()) && parse(h.incidentDate()) != null
                    && ChronoUnit.DAYS.between(parse(h.incidentDate()), ref) >= 0
                    && ChronoUnit.DAYS.between(parse(h.incidentDate()), ref) <= 14).count();
            if (recent >= 4) p.add("Spike: " + recent + " other '" + c.category() + "' complaints in the 14 days before this incident");
        }
        return p;
    }

    private interface Getter { String get(ComplaintDto h); }

    private void identifierPattern(List<String> p, String label, String value, List<ComplaintDto> pool, Getter g) {
        if (value == null || value.isBlank()) return;
        List<ComplaintDto> m = pool.stream().filter(h -> same(value, g.get(h))).toList();
        if (!m.isEmpty()) p.add("Suspect " + label + " '" + value + "' appears in " + m.size() + " earlier complaint(s), total reported loss Rs " + fmt(sumLoss(m)));
    }

    static Set<String> accounts(ComplaintDto c) {
        Set<String> s = new LinkedHashSet<>();
        if (c.suspectAccount() != null && !c.suspectAccount().isBlank()) s.add(Text.clean(c.suspectAccount()));
        if (c.transactions() != null)
            for (TxnDto t : c.transactions())
                if (t.toAccount() != null && !t.toAccount().isBlank()) s.add(Text.clean(t.toAccount()));
        return s;
    }

    static boolean same(String a, String b) {
        return a != null && b != null && !a.isBlank() && !b.isBlank() && Text.clean(a).equals(Text.clean(b));
    }

    static double sumLoss(List<ComplaintDto> l) { return l.stream().mapToDouble(ComplaintDto::amountLost).sum(); }
    static String fmt(double v) { return String.format("%,.0f", v); }

    static LocalDate parse(String s) {
        try { return s == null ? null : LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }

    static long daysBetween(String a, String b) {
        LocalDate x = parse(a), y = parse(b);
        return (x == null || y == null) ? -1 : Math.abs(ChronoUnit.DAYS.between(x, y));
    }

    private static Map<String, Double> tfidf(List<String> toks, Map<String, Integer> df, int n) {
        Map<String, Double> v = new HashMap<>();
        Map<String, Integer> tf = new HashMap<>();
        for (String t : toks) tf.merge(t, 1, Integer::sum);
        for (var e : tf.entrySet()) {
            double idf = Math.log((1.0 + n) / (1.0 + df.getOrDefault(e.getKey(), 0))) + 1.0;
            v.put(e.getKey(), e.getValue() * idf);
        }
        return v;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (var e : a.entrySet()) { na += e.getValue() * e.getValue(); Double o = b.get(e.getKey()); if (o != null) dot += e.getValue() * o; }
        for (double x : b.values()) nb += x * x;
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
