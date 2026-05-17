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

    /**
     * Cache of compiled regex patterns, keyed by {@code keyword + "\0" + mode}.
     * Uses {@link ConcurrentHashMap} so the cache is safe to share across threads if needed.
     * Compiling a pattern is expensive; caching avoids recompilation on every page of a crawl.
     */
    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /**
     * Returns (or creates and caches) the compiled regex pattern for the given keyword and mode.
     *
     * <p>For {@code exact} mode: a case-sensitive, literal-quoted pattern.
     * For all other modes: a case-insensitive, Unicode-aware, literal-quoted pattern.
     * {@link Pattern#quote} escapes any regex metacharacters in the keyword so it is always
     * treated as a plain string.
     *
     * @param keyword the search keyword; must not be {@code null}.
     * @param mode    {@code "exact"} for case-sensitive, anything else for case-insensitive.
     * @return the compiled {@link Pattern}.
     */
    private Pattern getPattern(String keyword, String mode) {
        return patternCache.computeIfAbsent(keyword + "\0" + mode, k ->
                mode.equals("exact")
                        ? Pattern.compile(Pattern.quote(keyword))
                        : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /**
     * Counts the number of times {@code keyword} appears in {@code text} using the specified mode.
     *
     * <p><b>Default mode:</b> runs both a regex (case-insensitive, Unicode-aware) pass and a
     * simplified (diacritic-stripped, punctuation-removed) substring pass, returning the higher
     * of the two counts. The higher result is taken because a text like {@code "cafe Café"} with
     * keyword {@code "cafe"} yields only 1 from regex (which misses the accented variant) but 2
     * from the simplified pass.
     *
     * <p><b>Exact mode:</b> case-sensitive regex only.
     *
     * <p><b>Fuzzy mode:</b> delegates to {@link #countFuzzyMatches}.
     *
     * @param text    the string to search; returns 0 if {@code null} or empty.
     * @param keyword the keyword to search for; returns 0 if {@code null} or empty.
     * @param mode    one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @return the number of keyword occurrences found; never negative.
     */
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
            // accented variants (e.g. "cafe" matches "Café"). Both are always counted and
            // the higher result is returned, because a text like "cafe Café" would yield
            // only 1 from regex (missing "Café") but 2 from the simplified pass.
            int count = 0;
            String processedText = text.replace(' ', ' ');
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

    /**
     * Finds up to {@code maxSnippets} context excerpts from {@code text} that contain the keyword.
     *
     * <p>Each snippet is built by taking 60 characters before and after the match position,
     * then extending both boundaries to the nearest word edge so snippets never cut mid-word.
     * Ellipsis ({@code ...}) is prepended/appended when the snippet does not reach the
     * start/end of the text.
     *
     * <p><b>Regex pass:</b> uses the same pattern as {@link #countMatches} for {@code default}
     * and {@code exact} modes.
     *
     * <p><b>Simplified pass:</b> runs only when the regex pass found nothing and mode is not
     * {@code exact}. Builds a character-position map from the simplified string back to the
     * original string so the snippet contains the original accented characters (not the stripped
     * version). For example, searching {@code "Tomas"} will find and highlight {@code "Tomáš"}
     * in its original form in the returned snippet.
     *
     * @param text        the string to search.
     * @param keyword     the keyword to search for.
     * @param mode        one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @param maxSnippets maximum number of distinct snippets to return.
     * @return a list of up to {@code maxSnippets} context strings; empty if no matches found.
     */
    public List<String> findSnippets(String text, String keyword, String mode, int maxSnippets) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) return List.of();

        // Flatten whitespace so snippets read cleanly as a single line
        String flat = text.replace(' ', ' ').replaceAll("[\r\n\t]+", " ").replaceAll(" {2,}", " ").trim();

        List<String> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Regex pass (handles default case-insensitive and exact)
        Pattern p = mode.equals("exact") ? getPattern(keyword, "exact") : getPattern(keyword, "default");
        Matcher m = p.matcher(flat);
        while (m.find() && results.size() < maxSnippets) {
            String snippet = buildSnippet(flat, m.start(), m.end());
            if (seen.add(snippet)) results.add(snippet);
        }

        // Simplified pass: catches diacritic variants the regex misses (e.g. "Tomas" matching "Tomáš").
        // Builds a per-character position map from simplified text back to flat so snippet boundaries
        // are accurate in the original string.
        if (results.isEmpty() && !mode.equals("exact")) {
            String simpleKeyword = superSimplify(keyword);
            if (simpleKeyword.length() >= 2) {
                StringBuilder simpleBuf = new StringBuilder(flat.length());
                int[] origPos = new int[flat.length() * 2 + 1]; // *2 for rare ligature decomposition
                int sLen = 0;
                for (int ci = 0; ci < flat.length(); ci++) {
                    String nfd = Normalizer.normalize(String.valueOf(flat.charAt(ci)), Normalizer.Form.NFD);
                    for (char nc : nfd.toCharArray()) {
                        int type = Character.getType(nc);
                        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                                || type == Character.ENCLOSING_MARK) continue;
                        char lc = Character.toLowerCase(nc);
                        if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9')) {
                            if (sLen < origPos.length) { origPos[sLen++] = ci; simpleBuf.append(lc); }
                        }
                    }
                }
                String simpleFlat = simpleBuf.toString();
                int idx = 0;
                while (results.size() < maxSnippets) {
                    idx = simpleFlat.indexOf(simpleKeyword, idx);
                    if (idx < 0) break;
                    int flatStart = origPos[idx];
                    int flatEnd = origPos[Math.min(idx + simpleKeyword.length() - 1, sLen - 1)] + 1;
                    String snippet = buildSnippet(flat, flatStart, flatEnd);
                    if (seen.add(snippet)) results.add(snippet);
                    idx++;
                }
            }
        }

        return results;
    }

    /**
     * Extracts a context excerpt centred on the character range [{@code start}, {@code end})
     * within {@code flat}.
     *
     * <p>Takes up to 60 characters before and after the match, then expands both boundaries
     * outward to the nearest space so the snippet does not cut a word in half. Adds
     * {@code "..."} at the beginning or end when the boundary does not reach the edge of the text.
     *
     * @param flat  the whitespace-flattened source string.
     * @param start the index of the first character of the match.
     * @param end   the index one past the last character of the match.
     * @return a trimmed snippet string.
     */
    private String buildSnippet(String flat, int start, int end) {
        int lo = Math.max(0, start - 60);
        int hi = Math.min(flat.length(), end + 60);
        while (lo > 0 && flat.charAt(lo - 1) != ' ') lo--;
        while (hi < flat.length() && flat.charAt(hi) != ' ') hi++;
        return (lo > 0 ? "..." : "") + flat.substring(lo, hi).trim() + (hi < flat.length() ? "..." : "");
    }

    /**
     * Counts keyword occurrences in fuzzy mode.
     *
     * <p><b>First pass:</b> uses a simplified (diacritic-stripped, punctuation-removed) substring
     * search. If any matches are found this way, returns the count immediately.
     *
     * <p><b>Second pass (Levenshtein):</b> if no substring matches were found, splits the text
     * into individual words and counts words within edit distance 1 (for keywords ≤ 4 characters)
     * or edit distance 2 (for longer keywords). An early exit skips words whose length difference
     * alone exceeds the threshold.
     *
     * @param text    the string to search.
     * @param keyword the keyword to match fuzzily.
     * @return the number of approximate matches found.
     */
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

    /**
     * Strips diacritics, lowercases, and removes all non-alphanumeric characters from {@code input}.
     *
     * <p>Uses Unicode NFD normalisation to decompose characters into base letter + combining
     * mark(s), then discards all combining marks ({@code \p{M}}). The result contains only
     * ASCII letters and digits.
     *
     * <p>Examples: {@code "Café"} → {@code "cafe"}, {@code "C++"} → {@code "c"},
     * {@code "Tomáš"} → {@code "tomas"}.
     *
     * @param input the string to simplify; {@code null} is treated as {@code ""}.
     * @return the simplified string; never {@code null}.
     */
    public String superSimplify(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        return normalized.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Same as {@link #superSimplify} but preserves spaces so the result can be split into words.
     * Used by fuzzy mode to tokenise the text before Levenshtein matching.
     *
     * <p>Non-alphanumeric, non-space characters are replaced with a space rather than removed,
     * so word boundaries around punctuation are preserved.
     *
     * @param input the string to simplify; {@code null} is treated as {@code ""}.
     * @return the simplified string with spaces preserved; never {@code null}.
     */
    private String simplifyWithSpaces(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        return normalized.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
    }

    /**
     * Computes the Levenshtein edit distance between two strings using dynamic programming.
     *
     * <p>The edit distance is the minimum number of single-character insertions, deletions,
     * or substitutions required to transform {@code s1} into {@code s2}. Used by fuzzy mode
     * to find words that are close to the keyword.
     *
     * @param s1 the first string.
     * @param s2 the second string.
     * @return the edit distance; 0 means the strings are identical.
     */
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
