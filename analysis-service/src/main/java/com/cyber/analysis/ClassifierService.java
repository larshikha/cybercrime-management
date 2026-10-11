package com.cyber.analysis;

import com.cyber.analysis.Dto.ComplaintDto;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Hybrid classifier: explainable weighted lexicon (domain knowledge) blended with a
 * Multinomial Naive Bayes model trained on the historical complaint corpus at request time.
 */
@Service
public class ClassifierService {

    public static final List<String> CATEGORIES = List.of(
            "ONLINE_FINANCIAL_FRAUD", "PHISHING", "IDENTITY_THEFT", "ACCOUNT_COMPROMISE", "SOCIAL_MEDIA_ABUSE", "OTHER");

    private static final Map<String, Map<String, Double>> LEXICON = new LinkedHashMap<>();

    static {
        LEXICON.put("ONLINE_FINANCIAL_FRAUD", weights(
                "upi", 1.0, "refund", 1.0, "qr code", 1.5, "debited", 1.0, "transferred", 1.0, "olx", 1.5,
                "investment", 1.5, "trading", 1.0, "lottery", 1.5, "loan app", 1.5, "cashback", 1.0,
                "scam", 1.0, "fraud", 0.8, "screen sharing", 1.5, "anydesk", 1.5, "credit card", 1.0,
                "paid", 0.6, "advance payment", 1.5, "money", 0.5, "crypto", 1.0, "ponzi", 1.5, "double your", 1.5,
                "bank account", 0.6, "wallet", 0.6, "payment", 0.7, "fake seller", 1.5, "part-time job", 1.0, "task", 0.4));
        LEXICON.put("PHISHING", weights(
                "phishing", 2.0, "fake website", 1.8, "link", 1.0, "sms", 0.8, "kyc", 1.2, "clicked", 1.2,
                "fake login", 1.8, "spoofed", 1.5, "verify your", 1.2, "update your", 1.0, "lookalike", 1.5,
                "suspicious email", 1.5, "customs", 0.8, "parcel", 0.8, "otp", 0.5, "blocked", 0.6, "http", 0.8,
                "email asking", 1.2, "fake page", 1.5, "credentials", 0.8));
        LEXICON.put("IDENTITY_THEFT", weights(
                "identity", 1.5, "aadhaar", 1.5, "pan card", 1.5, "fake id", 1.5, "impersonat", 1.5,
                "misused", 1.2, "in my name", 1.8, "sim swap", 1.8, "forged", 1.5, "stolen identity", 2.0,
                "passport", 1.0, "documents", 0.7, "duplicate sim", 1.5, "loan taken", 1.2, "cloned", 1.0, "pan number", 1.2));
        LEXICON.put("ACCOUNT_COMPROMISE", weights(
                "hacked", 2.0, "compromised", 1.8, "password", 1.2, "unauthorized login", 1.8, "unauthorised", 1.2,
                "unauthorized access", 1.8, "logged in from", 1.5, "2fa", 1.2, "locked out", 1.5,
                "takeover", 1.5, "changed my password", 1.8, "recovery email", 1.5, "email account", 1.0,
                "account access", 1.2, "no longer access", 1.5, "login alert", 1.4, "new device", 1.0));
        LEXICON.put("SOCIAL_MEDIA_ABUSE", weights(
                "instagram", 1.0, "facebook", 1.0, "harass", 1.8, "morph", 2.0, "threat", 1.0, "blackmail", 1.6,
                "abusive", 1.5, "cyberbullying", 2.0, "fake profile", 1.8, "defam", 1.5, "sextortion", 2.0,
                "obscene", 1.5, "stalking", 1.8, "trolling", 1.5, "leaked photos", 1.8, "whatsapp group", 0.8,
                "viral", 1.0, "post", 0.4, "profile", 0.6, "photos", 0.8, "messages", 0.4));
    }

    private static Map<String, Double> weights(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    public record Result(String category, double confidence, Map<String, Double> scores, List<String> keywords) {}

    public Result classify(ComplaintDto c, List<ComplaintDto> history) {
        String text = Text.normalize(c.description());

        // 1. Lexicon
        Map<String, Double> lex = new LinkedHashMap<>();
        Map<String, List<String>> hits = new LinkedHashMap<>();
        double total = 0;
        for (var e : LEXICON.entrySet()) {
            double s = 0;
            List<String> h = new ArrayList<>();
            for (var kw : e.getValue().entrySet()) {
                if (text.contains(kw.getKey())) {
                    s += kw.getValue();
                    h.add(kw.getKey());
                }
            }
            lex.put(e.getKey(), s);
            hits.put(e.getKey(), h);
            total += s;
        }
        Map<String, Double> lexProb = new LinkedHashMap<>();
        for (String cat : CATEGORIES) {
            if (cat.equals("OTHER")) continue;
            lexProb.put(cat, total > 0 ? lex.get(cat) / total : 0.0);
        }

        // Identifier hints: phishing URL / UPI present nudge the lexicon
        if (notBlank(c.suspectUrl()) && total > 0) bump(lexProb, "PHISHING", 0.15);
        if (notBlank(c.suspectUpi()) && total > 0) bump(lexProb, "ONLINE_FINANCIAL_FRAUD", 0.10);
        if (c.amountLost() > 0 && total > 0) bump(lexProb, "ONLINE_FINANCIAL_FRAUD", 0.05);

        // 2. Naive Bayes on history
        Map<String, Double> nb = naiveBayes(text, history);

        Map<String, Double> blended = new LinkedHashMap<>();
        double sum = 0;
        for (String cat : CATEGORIES) {
            if (cat.equals("OTHER")) continue;
            double v = nb.isEmpty() ? lexProb.get(cat) : 0.6 * lexProb.get(cat) + 0.4 * nb.getOrDefault(cat, 0.0);
            blended.put(cat, v);
            sum += v;
        }
        Map<String, Double> norm = new LinkedHashMap<>();
        for (var e : blended.entrySet()) norm.put(e.getKey(), sum > 0 ? round(e.getValue() / sum) : 0.0);

        String best = "OTHER";
        double bestP = 0;
        for (var e : norm.entrySet()) if (e.getValue() > bestP) { bestP = e.getValue(); best = e.getKey(); }
        if (total == 0 && nb.isEmpty()) { best = "OTHER"; bestP = 0.5; }
        if (total == 0 && !nb.isEmpty()) {
            // lexicon silent: trust NB alone
            for (var e : nb.entrySet()) if (e.getValue() >= bestP) { bestP = e.getValue(); best = e.getKey(); }
            bestP = round(bestP);
        }
        norm.put("OTHER", best.equals("OTHER") ? bestP : 0.0);
        return new Result(best, bestP, norm, hits.getOrDefault(best, List.of()));
    }

    private Map<String, Double> naiveBayes(String text, List<ComplaintDto> history) {
        List<ComplaintDto> train = new ArrayList<>();
        for (ComplaintDto h : history)
            if (h.category() != null && !h.category().isBlank() && !h.category().equals("OTHER") && h.description() != null)
                train.add(h);
        if (train.size() < 10) return Map.of();

        Map<String, Integer> docCount = new HashMap<>();
        Map<String, Map<String, Integer>> wordCount = new HashMap<>();
        Map<String, Integer> totalWords = new HashMap<>();
        Set<String> vocab = new HashSet<>();
        for (ComplaintDto h : train) {
            docCount.merge(h.category(), 1, Integer::sum);
            for (String t : Text.tokens(h.description())) {
                wordCount.computeIfAbsent(h.category(), k -> new HashMap<>()).merge(t, 1, Integer::sum);
                totalWords.merge(h.category(), 1, Integer::sum);
                vocab.add(t);
            }
        }
        List<String> qt = Text.tokens(text);
        Map<String, Double> logp = new HashMap<>();
        double max = -1e18;
        for (String cat : docCount.keySet()) {
            double lp = Math.log((double) docCount.get(cat) / train.size());
            Map<String, Integer> wc = wordCount.getOrDefault(cat, Map.of());
            int tw = totalWords.getOrDefault(cat, 0);
            for (String t : qt) {
                if (!vocab.contains(t)) continue;
                lp += Math.log((wc.getOrDefault(t, 0) + 1.0) / (tw + vocab.size()));
            }
            logp.put(cat, lp);
            max = Math.max(max, lp);
        }
        double z = 0;
        Map<String, Double> p = new HashMap<>();
        for (var e : logp.entrySet()) {
            double v = Math.exp(e.getValue() - max);
            p.put(e.getKey(), v);
            z += v;
        }
        for (var e : p.entrySet()) e.setValue(e.getValue() / z);
        return p;
    }

    private static void bump(Map<String, Double> m, String k, double v) { m.merge(k, v, Double::sum); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
