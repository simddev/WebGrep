package com.webgrep.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts keyword occurrences in a string using one of three pluggable matching strategies.
 *
 * <ul>
 *   <li><b>default</b> — Case-insensitive with Unicode and diacritic support. {@code cafe}
 *       matches {@code Café}, {@code CAFE}, {@code café}. If no match is found via regex, a
 *       simplified (diacritic-stripped, punctuation-removed) fallback pass is attempted.</li>
 *   <li><b>exact</b> — Strict case-sensitive literal match. {@code hello} does not match
 *       {@code Hello}.</li>
 *   <li><b>fuzzy</b> — First tries a normalised substring match; if that fails, splits the
 *       text into words and accepts any word within Levenshtein edit distance 1 (for keywords
 *       ≤ 4 characters) or 2 (longer keywords). Catches common typos and spelling variants.</li>
 * </ul>
 *
 * <p>Compiled {@link java.util.regex.Pattern} objects are cached for the most recently used
 * keyword/mode pair to avoid recompilation on repeated calls from a scan loop.
 */
public class MatchEngine {

    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private Pattern getPattern(String keyword, String mode) {
        return patternCache.computeIfAbsent(keyword + "\0" + mode, k ->
                mode.equals("exact")
                        ? Pattern.compile(Pattern.quote(keyword))
                        : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    public int countMatches(String text, String keyword, String mode) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return 0;
        }

        if (mode.equals("exact")) {
            int count = 0;
            Matcher matcher = getPattern(keyword, mode).matcher(text);
            while (matcher.find()) {
                count++;
            }
            return count;
        } else if (mode.equals("fuzzy")) {
            return countFuzzyMatches(text, keyword);
        } else {
            // Default: case-insensitive with Unicode and diacritic support.
            // Regex handles exact case-insensitive matches; the simplified pass catches
            // accented variants (e.g. "cafe" matches "Caf\u00E9"). Both are always counted and
            // the higher result is returned, because a text like "cafe Caf\u00E9" would yield
            // only 1 from regex (missing "Caf\u00E9") but 2 from the simplified pass.
            int count = 0;
            String processedText = text.replace('\u00A0', ' ');
            Matcher matcher = getPattern(keyword, mode).matcher(processedText);
            while (matcher.find()) {
                count++;
            }

            String simpleKeyword = superSimplify(keyword);
            String simpleText = superSimplify(text);
            // Require at least 2 chars after stripping so that keywords like "(C)" or "C++"
            // don't degrade to a single letter that matches every word in the text.
            // The simplified pass is always needed: even a pure-ASCII keyword like "cafe"
            // must match accented variants in the text ("Café"), which regex cannot find.
            if (simpleKeyword.length() >= 2) {
                int simpleCount = 0;
                int idx = 0;
                while ((idx = simpleText.indexOf(simpleKeyword, idx)) != -1) {
                    simpleCount++;
                    idx += simpleKeyword.length();
                }
                count = Math.max(count, simpleCount);
            }
            return count;
        }
    }

    public List<String> findSnippets(String text, String keyword, String mode, int maxSnippets) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) return List.of();

        // Flatten whitespace so snippets read cleanly as a single line
        String flat = text.replace(' ', ' ').replaceAll("[\r\n\t]+", " ").replaceAll(" {2,}", " ").trim();

        List<String> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Pattern p = mode.equals("exact") ? getPattern(keyword, "exact") : getPattern(keyword, "default");
        Matcher m = p.matcher(flat);
        while (m.find() && results.size() < maxSnippets) {
            int lo = Math.max(0, m.start() - 60);
            int hi = Math.min(flat.length(), m.end() + 60);
            while (lo > 0 && flat.charAt(lo - 1) != ' ') lo--;
            while (hi < flat.length() && flat.charAt(hi) != ' ') hi++;
            String snippet = (lo > 0 ? "..." : "") + flat.substring(lo, hi).trim() + (hi < flat.length() ? "..." : "");
            if (seen.add(snippet)) results.add(snippet);
        }
        return results;
    }

    private int countFuzzyMatches(String text, String keyword) {
        String superSimpleKeyword = superSimplify(keyword);
        String superSimpleText = superSimplify(text);

        // Same guard as default mode: a 1-char simplified keyword (e.g. "C++" → "c") is not meaningful.
        if (superSimpleKeyword.length() < 2) return 0;

        int count = 0;
        int idx = 0;
        while ((idx = superSimpleText.indexOf(superSimpleKeyword, idx)) != -1) {
            count++;
            idx += superSimpleKeyword.length();
        }

        if (count == 0) {
            String normalizedKeyword = superSimpleKeyword;
            String normalizedTextWithSpaces = simplifyWithSpaces(text);
            String[] words = normalizedTextWithSpaces.split("\\s+");

            int threshold = normalizedKeyword.length() <= 4 ? 1 : 2;
            for (String word : words) {
                if (word.isEmpty()) continue;
                // skip words whose length difference alone exceeds the threshold
                if (Math.abs(word.length() - normalizedKeyword.length()) > threshold) continue;
                if (levenshteinDistance(word, normalizedKeyword) <= threshold) {
                    count++;
                }
            }
        }
        return count;
    }

    public String superSimplify(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        return normalized.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String simplifyWithSpaces(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        return normalized.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(Math.min(
                                    dp[i - 1][j] + 1,
                                    dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1));
                }
            }
        }
        return dp[s1.length()][s2.length()];
    }
}
