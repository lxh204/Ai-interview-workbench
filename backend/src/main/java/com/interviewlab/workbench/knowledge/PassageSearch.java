package com.interviewlab.workbench.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class PassageSearch {
    private static final Pattern WORDS = Pattern.compile("\\p{IsHan}+|[a-z0-9][a-z0-9_+-]*");
    private static final int SIZE = 420;
    private static final int OVERLAP = 60;

    private PassageSearch() {}

    static List<String> split(String text) {
        int[] characters = text.strip().codePoints().toArray();
        var chunks = new ArrayList<String>();
        for (int start = 0; start < characters.length;) {
            int end = Math.min(start + SIZE, characters.length);
            if (end < characters.length) {
                for (int boundary = end - 1; boundary >= start + SIZE - 70; boundary--) {
                    int c = characters[boundary];
                    if (c == '\n' || c == '。' || c == '？' || c == '！') {
                        end = boundary + 1;
                        break;
                    }
                }
            }
            String chunk = new String(characters, start, end - start).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end == characters.length) {
                break;
            }
            start = end - OVERLAP;
        }
        return chunks;
    }

    static List<Passage> find(String question, List<Passage> passages, double[] queryVector) {
        Set<String> queryTerms = terms(question);
        List<Set<String>> passageTerms = passages.stream().map(p -> terms(p.text())).toList();
        var weights = new HashMap<String, Double>();
        for (String token : queryTerms) {
            long frequency = passageTerms.stream().filter(terms -> terms.contains(token)).count();
            if (frequency > 0) {
                weights.put(token, 1 + Math.log((passages.size() + 1.0) / (frequency + 1.0)));
            }
        }
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        var scored = new ArrayList<Ranked>();
        for (int i = 0; i < passages.size(); i++) {
            Passage passage = passages.get(i);
            Set<String> candidates = passageTerms.get(i);
            double lexical = total == 0 ? 0 : weights.entrySet().stream()
                .filter(entry -> candidates.contains(entry.getKey())).mapToDouble(entry -> entry.getValue()).sum() / total;
            double semantic = cosine(queryVector, passage.vector());
            if (lexical > 0 || semantic >= 0.48) {
                scored.add(new Ranked(passage, lexical + 0.65 * Math.max(0, semantic)));
            }
        }
        var uniqueText = new HashSet<String>();
        return scored.stream().sorted(Comparator.comparingDouble(Ranked::score).reversed())
            .map(Ranked::passage).filter(passage -> uniqueText.add(passage.text())).limit(3).toList();
    }

    private static Set<String> terms(String text) {
        var terms = new HashSet<String>();
        var words = WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = words.group();
            int[] points = word.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN && points.length > 1) {
                for (int i = 0; i < points.length - 1; i++) {
                    terms.add(new String(points, i, 2));
                }
            } else {
                terms.add(word);
            }
        }
        return terms;
    }

    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double product = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            product += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : product / Math.sqrt(normA * normB);
    }

    record Passage(String documentId, String title, int chunkIndex, String text, double[] vector) {}
    private record Ranked(Passage passage, double score) {}
}
