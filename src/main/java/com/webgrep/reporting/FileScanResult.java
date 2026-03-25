package com.webgrep.reporting;

import java.util.List;

/**
 * The matches found within a single file during a folder scan.
 */
public record FileScanResult(String path, List<FileMatch> matches) {
    public int totalMatches() {
        return matches.stream().mapToInt(FileMatch::count).sum();
    }
}
