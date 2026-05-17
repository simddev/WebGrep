package com.webgrep.reporting;

/**
 * A single line in a local file (or a page in a multi-page document) that contains at least
 * one keyword match.
 *
 * <p>Instances are created by {@link com.webgrep.Main#findLineMatches} and collected into a
 * {@link FileScanResult} for reporting.
 *
 * @param page    1-based page number as reported by Apache Tika. {@code 0} when the source
 *                document has no page structure (plain text, single-page files, etc.).
 * @param line    1-based line number within the page (or within the whole file when {@code page == 0}).
 * @param count   Number of keyword occurrences found on this line.
 * @param snippet The trimmed content of the matching line, truncated to 120 characters.
 */
public record FileMatch(int page, int line, int count, String snippet) {}
