package com.cyber.analysis;

import java.net.URI;
import java.util.*;

final class Text {
    private Text() {}

    static final Set<String> STOP = new HashSet<>(Arrays.asList("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "my", "i", "me",
            "is", "was", "are", "were", "it", "this", "that", "he", "she", "they", "we", "you", "be", "by", "at", "as", "from",
            "has", "have", "had", "said", "after", "then", "but", "not", "so", "rs", "inr", "his", "her", "their", "our", "your"));

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : normalize(s).split("[^a-z0-9@.]+")) {
            t = t.replaceAll("^\\.+|\\.+$", "");
            if (t.length() > 2 && !STOP.contains(t) && !t.matches("\\d+")) out.add(t);
        }
        return out;
    }

    static String domain(String url) {
        if (url == null || url.isBlank()) return "";
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (!u.contains("://")) u = "http://" + u;
        try {
            String h = URI.create(u).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    static String clean(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
