package com.webgrep.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds keyword occurrences in a string using one of three matching strategies.
 *
 * <ul>
 *   <li><b>default</b> - Case-insensitive with Unicode and diacritic support. {@code cafe}
 *       matches {@code Café}, {@code CAFE}, {@code café}. A case-insensitive literal pass runs
 *       alongside a simplified (diacritic-stripped, punctuation-as-space) pass, so accented and
 *       punctuated variants are found too.</li>
 *   <li><b>exact</b> - Case-sensitive literal match. {@code hello} does not match
 *       {@code Hello}.</li>
 *   <li><b>fuzzy</b> - First looks for the keyword with case, diacritics and punctuation inside
 *       words ignored ({@code node.js} finds {@code nodejs}). If that finds nothing, accepts any
 *       word within Levenshtein edit distance 1 (keywords of up to 4 characters) or 2 (longer
 *       keywords). Catches common typos and spelling variants.</li>
 * </ul>
 *
 * <p>In every mode, whitespace inside the keyword matches any run of whitespace in the text.
 * A phrase therefore still matches when the text breaks it across lines - as PDF text does at
 * the end of every printed line - or pads it with extra spaces or tabs.
 *
 * <p>Two entry points share one matching core: {@link #countMatches} answers "how many" as
 * cheaply as possible, and {@link #findMatches} answers "where", which snippets and per-line
 * reporting need. Compiled patterns are cached per keyword and mode, so a crawl compiles each
 * pattern once rather than once per page.
 */
public class MatchEngine {

    /**
     * One keyword occurrence: the half-open character range {@code [start, end)} it occupies in
     * the searched text.
     *
     * @param start index of the first character of the occurrence.
     * @param end   index one past the last character of the occurrence.
     */
    public record Match(int start, int end) {}

    /**
     * Regex for one run of whitespace: the ASCII whitespace of {@code \s} plus the Unicode space
     * separators ({@code \p{Zs}}, U+00A0 no-break space among them) and the line and paragraph
     * separators. The set is spelled out because an explicit character set is checked faster
     * than a Unicode property, and this class runs on every page searched for a phrase.
     */
    private static final String WHITESPACE_RUN =
            "[\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+";

    /** Compiled form of {@link #WHITESPACE_RUN}, used to split keywords into words. */
    private static final Pattern WHITESPACE = Pattern.compile(WHITESPACE_RUN);

    /** Characters of context kept on each side of a match in a snippet, before snapping to a word edge. */
    private static final int SNIPPET_CONTEXT = 60;

    /**
     * How far a snippet edge may move outward to avoid cutting a word in half. Text without
     * spaces (Chinese, Japanese, minified code) has no word edge to find, so the search stops
     * here rather than swallowing the whole page and pushing the match out of the snippet.
     */
    private static final int MAX_EDGE_EXTENSION = 40;

    /** Longest snippet returned by {@link #findSnippets}; longer ones are cut and end in "...". */
    private static final int MAX_SNIPPET_LENGTH = 500;

    /**
     * How a run of non-alphanumeric characters is represented in a simplified string.
     */
    private enum Separators {
        /** Every run is dropped: {@code "node.js"} becomes {@code "nodejs"}. */
        NONE,
        /** Every run becomes a single space: {@code "real-time"} becomes {@code "real time"}. */
        SPACE,
        /**
         * A run containing whitespace becomes a single space, while punctuation on its own is
         * dropped: {@code "node.js"} becomes {@code "nodejs"} but {@code "so far"} stays two words.
         */
        WHITESPACE_ONLY
    }

    /** Marker in {@link #FOLD}: the character is a combining mark and contributes nothing. */
    private static final String FOLD_MARK = "";

    /** Marker in {@link #FOLD}: the character is punctuation or a symbol, i.e. a soft separator. */
    private static final String FOLD_PUNCT = "\0";

    /** Marker in {@link #FOLD}: the character is whitespace, i.e. a hard separator. */
    private static final String FOLD_SPACE = "\1";

    /**
     * What each non-ASCII BMP character contributes to a simplified string, computed on first
     * use. An entry is either the lowercase ASCII letters and digits the character decomposes to
     * (for example {@code "e"} for {@code é}) or one of {@link #FOLD_MARK}, {@link #FOLD_PUNCT}
     * and {@link #FOLD_SPACE}. Caching per character avoids running {@link Normalizer} over the
     * text: real pages use a small alphabet, so after the first few hundred characters every
     * lookup is a hit. Concurrent first lookups may compute the same entry twice, which is
     * harmless because the value is a pure function of the character.
     */
    private static final String[] FOLD = new String[Character.MAX_VALUE + 1];

    /**
     * Cache of compiled regex patterns, keyed by {@code keyword + "\0" + kind}.
     * Uses {@link ConcurrentHashMap} so the cache is safe to share across threads if needed.
     */
    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /**
     * Counts the occurrences of {@code keyword} in {@code text}.
     *
     * <p><b>Default mode:</b> runs a case-insensitive literal pass and, unless it is provably
     * redundant, a simplified (diacritic-stripped, punctuation-as-space) pass, and returns the
     * higher of the two counts. {@code "cafe Café"} with keyword {@code "cafe"} yields 1 from the
     * literal pass but 2 from the simplified pass. The simplified pass is skipped for keywords
     * containing ASCII punctuation ({@code node.js}, {@code C++}), whose meaning it would change.
     *
     * <p><b>Exact mode:</b> case-sensitive literal pass only.
     *
     * <p><b>Fuzzy mode:</b> the close-match pass if it finds anything, otherwise the typo pass;
     * see {@link #findCloseMatches} and {@link #findTypoMatches}.
     *
     * <p>The count always equals the size of {@link #findMatches} for the same arguments, except
     * in default mode for exotic case foldings (such as the long s, {@code ſ}) that only one of
     * the two passes understands; there the count is the larger pass and the located matches are
     * the union of both.
     *
     * @param text    the string to search; returns 0 if {@code null} or empty.
     * @param keyword the keyword to search for; returns 0 if {@code null} or blank.
     * @param mode    one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @return the number of keyword occurrences found; never negative.
     */
    public int countMatches(String text, String keyword, String mode) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isBlank()) {
            return 0;
        }

        if (mode.equals("exact")) {
            return countRegex(text, getPattern(keyword, true));
        } else if (mode.equals("fuzzy")) {
            Pattern close = fuzzyPattern(keyword);
            if (close == null) return 0;
            int count = countRegex(simplify(text, Separators.WHITESPACE_ONLY, false).text(), close);
            return count > 0 ? count : countTypoMatches(text, keyword);
        }

        int count = countRegex(text, getPattern(keyword, false));
        String simpleKeyword = simplifiedKeyword(keyword);
        if (simpleKeyword != null && !simplifiedPassIsRedundant(text, simpleKeyword, keyword)) {
            String simpleText = simplify(text, Separators.SPACE, false).text();
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

    /**
     * Locates every occurrence of {@code keyword} in {@code text}.
     *
     * <p>Uses the same rules as {@link #countMatches}. In default mode the result is the union of
     * the literal and simplified passes: an occurrence found by both is reported once, with the
     * literal pass's range.
     *
     * @param text    the string to search.
     * @param keyword the keyword to search for.
     * @param mode    one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @return the non-overlapping occurrences in text order; empty if there are none.
     */
    public List<Match> findMatches(String text, String keyword, String mode) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isBlank()) {
            return List.of();
        }

        if (mode.equals("exact")) {
            return regexMatches(text, getPattern(keyword, true));
        } else if (mode.equals("fuzzy")) {
            List<Match> close = findCloseMatches(text, keyword);
            return close.isEmpty() ? findTypoMatches(text, keyword) : close;
        }

        return withVariants(text, keyword, regexMatches(text, getPattern(keyword, false)));
    }

    /**
     * Default mode: adds the simplified pass's occurrences to the literal pass's, unless the
     * simplified pass must not run or provably adds nothing.
     *
     * @param text    the string being searched.
     * @param keyword the keyword.
     * @param literal every occurrence the case-insensitive literal pass found, in text order.
     * @return the union of both passes, in text order.
     */
    private List<Match> withVariants(String text, String keyword, List<Match> literal) {
        String simpleKeyword = simplifiedKeyword(keyword);
        if (simpleKeyword == null || simplifiedPassIsRedundant(text, simpleKeyword, keyword)) {
            return literal;
        }
        Simplified simple = simplify(text, Separators.SPACE, true);
        List<Match> variants = new ArrayList<>();
        int idx = 0;
        while ((idx = simple.text().indexOf(simpleKeyword, idx)) != -1) {
            variants.add(simple.range(idx, idx + simpleKeyword.length()));
            idx += simpleKeyword.length();
        }
        return union(literal, variants);
    }

    /**
     * Fuzzy mode, first pass: finds the keyword with case, diacritics and punctuation inside
     * words ignored.
     *
     * <p>{@code node.js}, {@code nodejs} and {@code Node-JS} all find each other, and the parts of
     * a multi-word keyword may be written together or apart ({@code real time} finds
     * {@code realtime}). Whitespace in the text is still a word boundary, though: {@code sofa}
     * does not match {@code "so far away"}, because the text has a space where the keyword has
     * none.
     *
     * @param text    the string to search.
     * @param keyword the keyword to search for.
     * @return the occurrences in text order; empty if there are none, or if the keyword has
     *         fewer than two letters and digits (so {@code "C++"} cannot match every "c").
     */
    public List<Match> findCloseMatches(String text, String keyword) {
        Pattern close = keyword == null ? null : fuzzyPattern(keyword);
        if (close == null || text == null || text.isEmpty()) return List.of();
        Simplified simple = simplify(text, Separators.WHITESPACE_ONLY, true);
        List<Match> matches = new ArrayList<>();
        Matcher m = close.matcher(simple.text());
        while (m.find()) {
            matches.add(simple.range(m.start(), m.end()));
        }
        return matches;
    }

    /**
     * Fuzzy mode, second pass: finds words within a small edit distance of the keyword.
     *
     * <p>The text is split into words at every non-alphanumeric character, and each word is
     * compared with the keyword after both are simplified. A word matches when its Levenshtein
     * distance from the keyword is at most 1 (keywords of up to 4 characters) or 2 (longer
     * keywords). Fuzzy mode uses this pass only when {@link #findCloseMatches} finds nothing, so
     * a correctly spelled occurrence is not drowned out by every word one letter away from it.
     *
     * @param text    the string to search.
     * @param keyword the keyword to search for.
     * @return the matching words in text order; empty if there are none.
     */
    public List<Match> findTypoMatches(String text, String keyword) {
        String target = superSimplify(keyword);
        if (target.length() < 2 || text == null || text.isEmpty()) return List.of();
        Simplified simple = simplify(text, Separators.SPACE, true);
        List<Match> matches = new ArrayList<>();
        forEachTypoWord(simple.text(), target, (start, end) -> matches.add(simple.range(start, end)));
        return matches;
    }

    /**
     * Finds up to {@code maxSnippets} distinct context excerpts around keyword occurrences.
     *
     * <p>The text's whitespace is flattened first, because a snippet is printed on one line.
     * Each snippet keeps 60 characters of context on either side of the occurrence, widened to
     * the nearest word edge when one is close by. {@code ...} marks a side that does not reach
     * the start or end of the text. Occurrences come from the same passes as
     * {@link #findMatches}, so every mode, including fuzzy typo matches, produces snippets for
     * the occurrences it counts.
     *
     * <p>In default and exact mode the literal occurrences are collected first; when they alone
     * fill every slot, they are returned without running the simplified pass. Otherwise the
     * snippets follow the complete list of occurrences in text order.
     *
     * <p>Snippets keep the original characters: searching {@code "Tomas"} shows {@code "Tomáš"}.
     * Two occurrences close enough together to produce the same snippet are shown once.
     *
     * @param text        the string to search.
     * @param keyword     the keyword to search for.
     * @param mode        one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @param maxSnippets maximum number of distinct snippets to return.
     * @return a list of up to {@code maxSnippets} context strings; empty if there are no
     *         occurrences.
     */
    public List<String> findSnippets(String text, String keyword, String mode, int maxSnippets) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isBlank()) return List.of();

        String flat = flattenWhitespace(text);
        List<Match> matches;
        if (mode.equals("fuzzy")) {
            matches = findMatches(flat, keyword, mode);
        } else {
            // Literal occurrences first. A page with many of them fills every snippet slot from
            // this pass alone, which saves building the simplified pass's position map - an int
            // per character of page text - only to throw it away.
            List<Match> literal = new ArrayList<>();
            List<String> quick = new ArrayList<>();
            Set<String> quickSeen = new LinkedHashSet<>();
            Matcher m = getPattern(keyword, mode.equals("exact")).matcher(flat);
            while (m.find()) {
                literal.add(new Match(m.start(), m.end()));
                String snippet = buildSnippet(flat, m.start(), m.end());
                if (quickSeen.add(snippet)) quick.add(snippet);
                if (quick.size() >= maxSnippets) return quick;
            }
            matches = mode.equals("exact") ? literal : withVariants(flat, keyword, literal);
            // Nothing beyond the literal occurrences: their snippets are already collected.
            if (matches == literal) return quick;
        }

        List<String> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Match match : matches) {
            if (results.size() >= maxSnippets) break;
            String snippet = buildSnippet(flat, match.start(), match.end());
            if (seen.add(snippet)) results.add(snippet);
        }
        return results;
    }

    /**
     * Strips diacritics, lowercases, and removes all non-alphanumeric characters from {@code input}.
     *
     * <p>Each character is decomposed (Unicode NFD) into a base letter and combining marks, the
     * marks are discarded, and only ASCII letters and digits are kept.
     *
     * <p>Examples: {@code "Café"} → {@code "cafe"}, {@code "C++"} → {@code "c"},
     * {@code "Tomáš"} → {@code "tomas"}.
     *
     * @param input the string to simplify; {@code null} is treated as {@code ""}.
     * @return the simplified string; never {@code null}.
     */
    public String superSimplify(String input) {
        return input == null ? "" : simplify(input, Separators.NONE, false).text();
    }

    // ── patterns ──────────────────────────────────────────────────────────────

    /**
     * Returns (or creates and caches) the literal pattern for a keyword.
     *
     * <p>Each word of the keyword is quoted, so regex metacharacters are always taken literally,
     * and every run of whitespace in the keyword becomes {@link #WHITESPACE_RUN}. A single-word
     * keyword therefore compiles to exactly {@code Pattern.quote(keyword)}.
     *
     * @param keyword the search keyword.
     * @param exact   {@code true} for a case-sensitive pattern, {@code false} for case-insensitive
     *                and Unicode-aware.
     * @return the compiled pattern.
     */
    private Pattern getPattern(String keyword, boolean exact) {
        return patternCache.computeIfAbsent(keyword + (exact ? "\0exact" : "\0default"), k -> {
            StringBuilder regex = new StringBuilder();
            Matcher ws = WHITESPACE.matcher(keyword);
            int last = 0;
            while (ws.find()) {
                if (ws.start() > last) regex.append(Pattern.quote(keyword.substring(last, ws.start())));
                regex.append(WHITESPACE_RUN);
                last = ws.end();
            }
            if (last < keyword.length()) regex.append(Pattern.quote(keyword.substring(last)));
            return exact
                    ? Pattern.compile(regex.toString())
                    : Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        });
    }

    /**
     * Returns (or creates and caches) the fuzzy close-match pattern for a keyword.
     *
     * <p>The keyword is split into its alphanumeric parts ({@code "node.js"} → {@code node},
     * {@code js}), and the parts are joined so that each boundary may match nothing or one space
     * in text simplified with {@link Separators#WHITESPACE_ONLY}: {@code "node ?js"}.
     *
     * @param keyword the search keyword.
     * @return the compiled pattern, or {@code null} if the keyword has fewer than two letters
     *         and digits.
     */
    private Pattern fuzzyPattern(String keyword) {
        String parts = simplify(keyword, Separators.SPACE, false).text().strip();
        if (parts.replace(" ", "").length() < 2) return null;
        return patternCache.computeIfAbsent(keyword + "\0fuzzy", k -> {
            StringBuilder regex = new StringBuilder();
            for (String part : parts.split(" ")) {
                if (regex.length() > 0) regex.append(" ?");
                regex.append(Pattern.quote(part));
            }
            return Pattern.compile(regex.toString());
        });
    }

    /**
     * Returns the keyword as the default-mode simplified pass searches for it, or {@code null}
     * when that pass must not run.
     *
     * <p>The pass is skipped for keywords containing ASCII punctuation ({@code "more*"},
     * {@code "node.js"}, {@code ".NET"}): turning that punctuation into a space would change the
     * intended search term. It is also skipped when the keyword simplifies to fewer than two
     * characters, so that a keyword such as {@code "(C)"} cannot match every "c".
     *
     * @param keyword the search keyword.
     * @return the simplified, stripped keyword, or {@code null}.
     */
    private static String simplifiedKeyword(String keyword) {
        if (hasAsciiSpecialChars(keyword)) return null;
        String simple = simplify(keyword, Separators.SPACE, false).text().strip();
        return simple.length() >= 2 ? simple : null;
    }

    // ── passes ────────────────────────────────────────────────────────────────

    /**
     * Counts the non-overlapping matches of a pattern.
     *
     * @param text    the string to search.
     * @param pattern the compiled pattern.
     * @return the number of matches.
     */
    private static int countRegex(String text, Pattern pattern) {
        int count = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) count++;
        return count;
    }

    /**
     * Lists the non-overlapping matches of a pattern.
     *
     * @param text    the string to search.
     * @param pattern the compiled pattern.
     * @return the matches, in text order.
     */
    private static List<Match> regexMatches(String text, Pattern pattern) {
        List<Match> matches = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) matches.add(new Match(m.start(), m.end()));
        return matches;
    }

    /**
     * Counts what {@link #findTypoMatches} would report, without tracking positions.
     *
     * @param text    the string to search.
     * @param keyword the keyword.
     * @return the number of words within the fuzzy edit distance of the keyword.
     */
    private int countTypoMatches(String text, String keyword) {
        String target = superSimplify(keyword);
        if (target.length() < 2) return 0;
        int[] count = {0};
        forEachTypoWord(simplify(text, Separators.SPACE, false).text(), target, (start, end) -> count[0]++);
        return count[0];
    }

    /** Receives the bounds {@code [start, end)} of a word in a simplified string. */
    @FunctionalInterface
    private interface WordSink {
        /**
         * Receives one word.
         *
         * @param start index of the word's first character.
         * @param end   index one past its last character.
         */
        void accept(int start, int end);
    }

    /**
     * Calls {@code sink} for every space-separated word of {@code simpleText} within the fuzzy
     * edit distance of {@code target}.
     *
     * @param simpleText text simplified with {@link Separators#SPACE}.
     * @param target     the keyword simplified with {@link Separators#NONE}; at least two characters.
     * @param sink       receives each matching word's bounds within {@code simpleText}.
     */
    private static void forEachTypoWord(String simpleText, String target, WordSink sink) {
        int threshold = target.length() <= 4 ? 1 : 2;
        int n = simpleText.length();
        int i = 0;
        while (i < n) {
            while (i < n && simpleText.charAt(i) == ' ') i++;
            int start = i;
            while (i < n && simpleText.charAt(i) != ' ') i++;
            int length = i - start;
            // Words whose length alone puts them out of reach are skipped without the DP.
            if (length > 0 && Math.abs(length - target.length()) <= threshold
                    && levenshteinDistance(simpleText, start, i, target) <= threshold) {
                sink.accept(start, i);
            }
        }
    }

    /**
     * Merges literal and simplified-pass occurrences. A simplified occurrence that overlaps a
     * literal one is the same occurrence seen twice and is dropped; the rest are interleaved in
     * text order. Neither input list overlaps itself.
     *
     * @param literal  occurrences from the literal pass, in text order.
     * @param variants occurrences from the simplified pass, in text order.
     * @return the union, in text order; the {@code literal} list itself when the simplified
     *         pass found nothing new, so callers can tell by identity.
     */
    private static List<Match> union(List<Match> literal, List<Match> variants) {
        if (variants.isEmpty()) return literal;
        if (literal.isEmpty()) return variants;
        List<Match> merged = new ArrayList<>(literal.size() + variants.size());
        int i = 0;
        for (Match variant : variants) {
            while (i < literal.size() && literal.get(i).start() <= variant.start()) merged.add(literal.get(i++));
            boolean overlapsPrevious = i > 0 && literal.get(i - 1).end() > variant.start();
            boolean overlapsNext = i < literal.size() && literal.get(i).start() < variant.end();
            if (!overlapsPrevious && !overlapsNext) merged.add(variant);
        }
        while (i < literal.size()) merged.add(literal.get(i++));
        return merged.size() == literal.size() ? literal : merged;
    }

    // ── simplification ────────────────────────────────────────────────────────

    /**
     * A simplified string and, when requested, where each of its characters came from.
     *
     * @param text        the simplified string.
     * @param sourceIndex for each character of {@code text}, its index in the source string;
     *                    {@code null} when positions were not tracked. May be longer than
     *                    {@code text}.
     */
    private record Simplified(String text, int[] sourceIndex) {

        /**
         * Maps a simplified range back to the source string. Both ends of a keyword match fall
         * on letters or digits, so the range covers whole source characters.
         *
         * @param from start of the range in the simplified string.
         * @param to   end of the range (exclusive) in the simplified string.
         * @return the corresponding range in the source string.
         */
        Match range(int from, int to) {
            return new Match(sourceIndex[from], sourceIndex[to - 1] + 1);
        }
    }

    /**
     * Reduces {@code input} to lowercase ASCII letters and digits, with runs of other characters
     * handled according to {@code separators}.
     *
     * <p>Each character is decomposed (NFD) and its combining marks are discarded, so accented
     * letters keep their base letter ({@code é} → {@code e}). A character that decomposes to no
     * ASCII letter or digit is a separator: whitespace is a hard separator, punctuation and
     * symbols (including letters of non-Latin scripts) are soft ones. Combining marks contribute
     * nothing at all. ASCII characters are handled inline; everything else goes through the
     * per-character cache {@link #FOLD}.
     *
     * <p>This single routine serves both counting and locating, so the two can never disagree
     * about what the simplified text is.
     *
     * @param input          the string to simplify; must not be {@code null}.
     * @param separators     how runs of non-alphanumeric characters are represented.
     * @param trackPositions whether to record each output character's source index.
     * @return the simplified string, with positions if requested.
     */
    private static Simplified simplify(String input, Separators separators, boolean trackPositions) {
        int n = input.length();
        StringBuilder sb = new StringBuilder(n);
        int[] pos = trackPositions ? new int[n + 1] : null;
        boolean lastWasSpace = false;
        for (int i = 0; i < n; i++) {
            char c = input.charAt(i);
            if (c < 128) {
                char lc = (c >= 'A' && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
                if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9')) {
                    pos = append(sb, pos, lc, i);
                    lastWasSpace = false;
                } else if (!lastWasSpace && breaksWord(separators, isAsciiWhitespace(c))) {
                    pos = append(sb, pos, ' ', i);
                    lastWasSpace = true;
                }
                continue;
            }
            String fold = FOLD[c];
            if (fold == null) {
                fold = computeFold(c);
                FOLD[c] = fold;
            }
            if (fold.isEmpty()) continue; // combining mark
            char first = fold.charAt(0);
            if (first > '\1') {
                for (int k = 0; k < fold.length(); k++) pos = append(sb, pos, fold.charAt(k), i);
                lastWasSpace = false;
            } else if (!lastWasSpace && breaksWord(separators, first == '\1')) {
                pos = append(sb, pos, ' ', i);
                lastWasSpace = true;
            }
        }
        return new Simplified(sb.toString(), pos);
    }

    /**
     * Appends one character to a simplified string under construction, recording its source
     * index when positions are tracked. A character that decomposes into several letters can
     * make the output longer than the input, so the position array grows on demand.
     *
     * @param sb          the simplified string so far.
     * @param pos         the position array, or {@code null} when positions are not tracked.
     * @param c           the character to append.
     * @param sourceIndex where {@code c} came from in the source string.
     * @return the position array, possibly reallocated; {@code null} when positions are not tracked.
     */
    private static int[] append(StringBuilder sb, int[] pos, char c, int sourceIndex) {
        if (pos != null) {
            if (sb.length() == pos.length) pos = Arrays.copyOf(pos, pos.length * 2);
            pos[sb.length()] = sourceIndex;
        }
        sb.append(c);
        return pos;
    }

    /**
     * Tells whether a separator becomes a space in the simplified string.
     *
     * @param separators the separator handling in effect.
     * @param whitespace whether the separator is whitespace (otherwise punctuation).
     * @return whether such a separator becomes a space.
     */
    private static boolean breaksWord(Separators separators, boolean whitespace) {
        return separators == Separators.SPACE || (separators == Separators.WHITESPACE_ONLY && whitespace);
    }

    /**
     * Recognises the ASCII whitespace characters, those matched by the regex class {@code \s}.
     *
     * @param c a character.
     * @return whether {@code c} is ASCII whitespace.
     */
    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    /**
     * Computes the {@link #FOLD} entry for one non-ASCII character.
     *
     * @param c a character of U+0080 or above.
     * @return the ASCII letters and digits it decomposes to, or a marker string.
     */
    private static String computeFold(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return FOLD_SPACE;
        String nfd = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        StringBuilder alnum = new StringBuilder(2);
        boolean visible = false;
        for (int k = 0; k < nfd.length(); k++) {
            char nc = nfd.charAt(k);
            int type = Character.getType(nc);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) continue;
            visible = true;
            char lc = Character.toLowerCase(nc);
            if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9')) alnum.append(lc);
        }
        if (alnum.length() > 0) return alnum.toString();
        return visible ? FOLD_PUNCT : FOLD_MARK;
    }

    /**
     * Returns {@code true} if {@code keyword} contains any ASCII character that is not a letter,
     * digit, or whitespace - for example {@code '*'}, {@code '.'}, {@code '+'}, {@code '#'}.
     *
     * <p>Used to gate the simplified pass in default mode: stripping such characters from the
     * keyword would change its meaning and produce false positives ({@code "more*"} →
     * {@code "more"}).
     *
     * @param keyword the search keyword.
     * @return whether it contains ASCII punctuation or symbols.
     */
    private static boolean hasAsciiSpecialChars(String keyword) {
        return keyword.chars().anyMatch(c -> c < 128 && !Character.isLetterOrDigit(c)
                && !isAsciiWhitespace((char) c));
    }

    /**
     * Returns {@code true} if every character of {@code s} is ASCII. Exits at the first
     * non-ASCII character, so the common "page contains a curly quote near the top" case
     * costs almost nothing.
     *
     * @param s the string to test.
     * @return whether every character is below U+0080.
     */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /**
     * Returns {@code true} when the simplified pass provably cannot find anything the
     * case-insensitive literal pass has not already found, so it can be skipped.
     *
     * <p>Simplification only lowercases and turns runs of non-alphanumeric characters into a
     * single space; it never joins two alphanumeric runs that were not already adjacent. So when
     * the text is pure ASCII and the simplified keyword is a single all-ASCII word, every
     * simplified match corresponds to the same contiguous characters in the original text - which
     * the case-insensitive literal pass matches too.
     *
     * <p>All three conditions are load-bearing:
     * <ul>
     *   <li>Non-ASCII text: keyword {@code cafe} must still find {@code café}.</li>
     *   <li>Non-ASCII keyword: keyword {@code café} must still find {@code cafe}.</li>
     *   <li>Multi-word keyword: keyword {@code real time} must still find {@code real-time}.</li>
     * </ul>
     *
     * @param text          the text being searched.
     * @param simpleKeyword the already-simplified, stripped keyword.
     * @param rawKeyword    the keyword as the user typed it.
     * @return {@code true} if the simplified pass can be skipped without changing the result.
     */
    private static boolean simplifiedPassIsRedundant(String text, String simpleKeyword, String rawKeyword) {
        return simpleKeyword.indexOf(' ') < 0 && isAscii(rawKeyword) && isAscii(text);
    }

    // ── snippets ──────────────────────────────────────────────────────────────

    /**
     * Collapses every run of whitespace - space, tab, CR, LF, form feed, vertical tab and
     * U+00A0 - into a single space, then trims.
     *
     * <p>Snippets are printed on one line, so the source text is flattened first. U+00A0
     * (no-break space) is included because SPA pages deliver it via the DOM, where it would
     * otherwise survive into snippet text as an invisible character. Form feed is included
     * because document text uses it between pages, and a snippet spanning a page break must
     * not glue the last word of one page to the first word of the next.
     *
     * <p>This is a single character pass: it runs over the full text of every page that has a
     * match.
     *
     * @param text the raw extracted text.
     * @return the flattened, trimmed text.
     */
    private static String flattenWhitespace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\r' || c == '\n' || c == '\t' || c == '\f' || c == 0x0B || c == ' ') {
                if (!lastWasSpace) { sb.append(' '); lastWasSpace = true; }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        // At most one leading and one trailing space can remain after collapsing.
        int from = 0, to = sb.length();
        while (from < to && sb.charAt(from) <= ' ') from++;
        while (to > from && sb.charAt(to - 1) <= ' ') to--;
        return sb.substring(from, to);
    }

    /**
     * Extracts a context excerpt around the character range [{@code start}, {@code end})
     * within {@code flat}.
     *
     * <p>Takes up to 60 characters before and after the occurrence, then moves each boundary
     * outward to the nearest space - but by no more than 40 characters, so text without spaces
     * keeps the occurrence in view. Adds {@code "..."} at a side that does not reach the edge of
     * the text, strips control characters, and caps the result at 500 characters.
     *
     * @param flat  the whitespace-flattened source string.
     * @param start the index of the first character of the occurrence.
     * @param end   the index one past the last character of the occurrence.
     * @return a trimmed snippet string.
     */
    private String buildSnippet(String flat, int start, int end) {
        int lo = snapLeft(flat, Math.max(0, start - SNIPPET_CONTEXT));
        int hi = snapRight(flat, Math.min(flat.length(), end + SNIPPET_CONTEXT));
        String snippet = (lo > 0 ? "..." : "") + flat.substring(lo, hi).trim() + (hi < flat.length() ? "..." : "");
        // Strip control characters (C0, DEL and C1) so that binary or minified content that
        // survived extraction cannot smuggle terminal escape sequences into the output.
        snippet = snippet.replaceAll("[\\x00-\\x1F\\x7F-\\x9F]", "");
        if (snippet.length() > MAX_SNIPPET_LENGTH) {
            int cut = MAX_SNIPPET_LENGTH - 3;
            if (Character.isHighSurrogate(snippet.charAt(cut - 1))) cut--;
            snippet = snippet.substring(0, cut) + "...";
        }
        return snippet;
    }

    /**
     * Moves a snippet's left boundary back to the start of a word, if one starts within
     * {@link #MAX_EDGE_EXTENSION} characters; otherwise keeps it, without splitting a surrogate pair.
     *
     * @param s  the flattened text.
     * @param lo the proposed left boundary.
     * @return the adjusted left boundary.
     */
    private static int snapLeft(String s, int lo) {
        for (int i = lo; i >= Math.max(0, lo - MAX_EDGE_EXTENSION); i--) {
            if (i == 0 || s.charAt(i - 1) == ' ') return i;
        }
        return Character.isLowSurrogate(s.charAt(lo)) ? lo - 1 : lo;
    }

    /**
     * Moves a snippet's right boundary forward to the end of a word, if one ends within
     * {@link #MAX_EDGE_EXTENSION} characters; otherwise keeps it, without splitting a surrogate pair.
     *
     * @param s  the flattened text.
     * @param hi the proposed right boundary (exclusive).
     * @return the adjusted right boundary.
     */
    private static int snapRight(String s, int hi) {
        for (int i = hi; i <= Math.min(s.length(), hi + MAX_EDGE_EXTENSION); i++) {
            if (i == s.length() || s.charAt(i) == ' ') return i;
        }
        return Character.isLowSurrogate(s.charAt(hi)) ? hi + 1 : hi;
    }

    /**
     * Computes the Levenshtein edit distance between the substring {@code a[from, to)} and
     * {@code b}: the minimum number of single-character insertions, deletions, or substitutions
     * that turn one into the other. Uses two rows of the dynamic-programming table.
     *
     * @param a    the string holding the first word.
     * @param from start of the first word in {@code a}.
     * @param to   end of the first word in {@code a}.
     * @param b    the second word.
     * @return the edit distance; 0 means the words are identical.
     */
    private static int levenshteinDistance(String a, int from, int to, String b) {
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = from; i < to; i++) {
            cur[0] = i - from + 1;
            char ca = a.charAt(i);
            for (int j = 1; j <= m; j++) {
                int substitution = prev[j - 1] + (ca == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), substitution);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }
}
