package com.webgrep.reporting;

import java.util.LinkedHashMap;
import java.util.Map;

public class CrawlResult {
    public enum ErrorType {
        NETWORK_ERROR,
        BLOCKED,
        PARSE_ERROR,
        SKIPPED_SIZE,
        SKIPPED_TYPE
    }

    public final Map<String, Integer> results = new LinkedHashMap<>();
    public final Map<String, String> blockedUrls = new LinkedHashMap<>();
    public final Map<ErrorType, Integer> errorCounts = new LinkedHashMap<>();
    public int visitedCount = 0;
    public int parsedCount = 0;
    public long durationMs = 0;
    public int stoppedAtMaxHits = 0; // 0 = ran to completion; >0 = stopped when this limit was hit

    public CrawlResult() {
        for (ErrorType type : ErrorType.values()) {
            errorCounts.put(type, 0);
        }
    }

    public void addMatch(String url, int count) {
        results.put(url, count);
    }

    public void addBlocked(String url, String reason) {
        blockedUrls.put(url, reason);
        incrementError(ErrorType.BLOCKED);
    }

    public void incrementError(ErrorType type) {
        errorCounts.put(type, errorCounts.get(type) + 1);
    }
}
