package com.webgrep.reporting;

import java.util.List;

/**
 * Aggregates all keyword matches found within a single file during a folder scan.
 *
 * <p>One {@code FileScanResult} is created per file that contains at least one match.
 * Files with zero matches are not represented. Used by {@link ReportWriter} to format
 * folder-scan output.
 *
 * @param path    Absolute path to the file on disk.
 * @param matches All lines in the file that contained at least one keyword match,
 *                in the order they were found (top to bottom, page by page).
 */
public record FileScanResult(String path, List<FileMatch> matches) {

    /**
     * Returns the total number of keyword occurrences across all matching lines in this file.
     * Sums {@link FileMatch#count()} for every entry in {@link #matches()}.
     *
     * @return total match count for this file; always {@code >= 1} since files with zero
     *         matches are never stored in a {@code FileScanResult}.
     */
    public int totalMatches() {
        return matches.stream().mapToInt(FileMatch::count).sum();
    }
}
