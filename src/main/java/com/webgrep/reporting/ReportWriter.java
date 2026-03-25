package com.webgrep.reporting;

import com.webgrep.config.CliOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportWriter {

    public void printTextOutput(CrawlResult crawlResult) {
        Map<String, Integer> results = crawlResult.results;
        int totalCount = crawlResult.getTotalMatches();

        System.out.println("--- WebGrep Results ---");
        System.out.println("Duration: " + formatDuration(crawlResult.durationMs));
        System.out.println("Total matches found: " + totalCount);
        System.out.println("Pages visited: " + crawlResult.visitedCount);
        System.out.println("  HTML pages: " + (crawlResult.parsedCount - crawlResult.docsCount));
        System.out.println("  Documents:  " + crawlResult.docsCount);

        boolean hasErrors = crawlResult.errorCounts.values().stream().anyMatch(c -> c > 0);
        if (hasErrors) {
            System.out.println("\nIssues:");
            for (CrawlResult.ErrorType type : CrawlResult.ErrorType.values()) {
                int count = crawlResult.errorCounts.get(type);
                if (count > 0) {
                    System.out.println("  " + errorLabel(type) + ": " + count);
                    if (type == CrawlResult.ErrorType.NETWORK_ERROR
                            && !crawlResult.networkErrorReasons.isEmpty()) {
                        crawlResult.networkErrorReasons.forEach((reason, n) ->
                            System.out.println("    " + reason + ": " + n));
                    }
                }
            }
        }

        if (totalCount > 0) {
            System.out.println("\nFound in:");
            results.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> System.out.println("  " + entry.getKey() + " (" + entry.getValue() + ")"));
        }

        if (!crawlResult.blockedUrls.isEmpty()) {
            System.out.println("\nBlocked / inaccessible:");
            // Group by reason so a flood of 403s doesn't spam hundreds of lines
            Map<String, List<String>> byReason = new LinkedHashMap<>();
            crawlResult.blockedUrls.forEach((url, reason) ->
                byReason.computeIfAbsent(reason, r -> new ArrayList<>()).add(url)
            );
            for (Map.Entry<String, List<String>> entry : byReason.entrySet()) {
                List<String> urls = entry.getValue();
                System.out.println("  " + entry.getKey() + " (" + urls.size() + "):");
                int shown = Math.min(urls.size(), 10);
                for (int i = 0; i < shown; i++) {
                    System.out.println("    " + urls.get(i));
                }
                if (urls.size() > shown) {
                    System.out.println("    ... and " + (urls.size() - shown) + " more");
                }
            }
        }

        if (crawlResult.stoppedAtMaxHits > 0) {
            System.out.println("\nNote: Search stopped early — max-hits limit of "
                    + crawlResult.stoppedAtMaxHits + " reached.");
        }
    }

    public void printJsonOutput(CrawlResult crawlResult, CliOptions options) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"query\": {\n");
        if (options.getFile() != null) {
            json.append("    \"file\": \"").append(escapeJson(options.getFile())).append("\",\n");
        } else {
            json.append("    \"url\": \"").append(escapeJson(options.getUrl())).append("\",\n");
            json.append("    \"depth\": ").append(options.getDepth()).append(",\n");
        }
        json.append("    \"keyword\": \"").append(escapeJson(options.getKeyword())).append("\",\n");
        json.append("    \"mode\": \"").append(escapeJson(options.getMode())).append("\"\n");
        json.append("  },\n");
        json.append("  \"stats\": {\n");
        json.append("    \"duration_ms\": ").append(crawlResult.durationMs).append(",\n");
        json.append("    \"total_matches\": ").append(crawlResult.getTotalMatches()).append(",\n");
        json.append("    \"pages_visited\": ").append(crawlResult.visitedCount).append(",\n");
        json.append("    \"pages_parsed\": ").append(crawlResult.parsedCount).append(",\n");
        json.append("    \"docs_parsed\": ").append(crawlResult.docsCount).append(",\n");
        json.append("    \"pages_blocked\": ").append(crawlResult.blockedUrls.size()).append(",\n");
        json.append("    \"errors\": {\n");
        CrawlResult.ErrorType[] types = CrawlResult.ErrorType.values();
        for (int i = 0; i < types.length; i++) {
            json.append("      \"").append(types[i].name().toLowerCase()).append("\": ").append(crawlResult.errorCounts.get(types[i]));
            if (i < types.length - 1 || !crawlResult.networkErrorReasons.isEmpty()) json.append(",");
            json.append("\n");
        }
        if (!crawlResult.networkErrorReasons.isEmpty()) {
            json.append("      \"network_error_reasons\": {\n");
            List<Map.Entry<String, Integer>> reasons = new ArrayList<>(crawlResult.networkErrorReasons.entrySet());
            for (int i = 0; i < reasons.size(); i++) {
                json.append("        \"").append(escapeJson(reasons.get(i).getKey())).append("\": ").append(reasons.get(i).getValue());
                if (i < reasons.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("      }\n");
        }
        json.append("    }\n");
        json.append("  },\n");
        if (crawlResult.stoppedAtMaxHits > 0) {
            json.append("  \"stopped_early\": \"max-hits limit of ").append(crawlResult.stoppedAtMaxHits).append(" reached\",\n");
        }
        json.append("  \"results\": [\n");

        List<Map.Entry<String, Integer>> sortedResults = new ArrayList<>(crawlResult.results.entrySet());
        sortedResults.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        for (int i = 0; i < sortedResults.size(); i++) {
            Map.Entry<String, Integer> entry = sortedResults.get(i);
            json.append("    { \"url\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ").append(entry.getValue()).append(" }");
            if (i < sortedResults.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"blocked\": [\n");
        List<Map.Entry<String, String>> blockedList = new ArrayList<>(crawlResult.blockedUrls.entrySet());
        for (int i = 0; i < blockedList.size(); i++) {
            Map.Entry<String, String> entry = blockedList.get(i);
            json.append("    { \"url\": \"").append(escapeJson(entry.getKey())).append("\", \"reason\": \"").append(escapeJson(entry.getValue())).append("\" }");
            if (i < blockedList.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        System.out.println(json.toString());
    }

    public void printFolderTextOutput(CliOptions options, List<FileScanResult> results,
                                      int filesScanned, int filesSkipped, long durationMs) {
        int totalMatches = results.stream().mapToInt(FileScanResult::totalMatches).sum();

        System.out.println("--- WebGrep Results ---");
        System.out.println("Duration: " + formatDuration(durationMs));
        System.out.println("Folder: " + options.getFolder());
        System.out.println("Files scanned: " + filesScanned);
        if (filesSkipped > 0)
            System.out.println("  Skipped (too large): " + filesSkipped);
        System.out.println("  With matches: " + results.size());
        System.out.println("Total matches found: " + totalMatches);

        if (results.isEmpty()) return;

        for (FileScanResult file : results) {
            boolean hasPages = file.matches().stream().anyMatch(m -> m.page() > 0);
            System.out.println("\n" + file.path() + "  (" + file.totalMatches() + " match"
                    + (file.totalMatches() == 1 ? "" : "es") + ")");
            for (FileMatch m : file.matches()) {
                String loc = hasPages
                        ? String.format("p.%d, l.%d", m.page(), m.line())
                        : String.format("l.%d", m.line());
                String countStr = m.count() == 1 ? "(1 match)" : "(" + m.count() + " matches)";
                System.out.printf("  %-16s  %s  \"%s\"%n", loc, countStr, m.snippet());
            }
        }
    }

    public void printFolderJsonOutput(CliOptions options, List<FileScanResult> results,
                                      int filesScanned, int filesSkipped, long durationMs) {
        int totalMatches = results.stream().mapToInt(FileScanResult::totalMatches).sum();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"query\": {\n");
        json.append("    \"folder\": \"").append(escapeJson(options.getFolder())).append("\",\n");
        json.append("    \"keyword\": \"").append(escapeJson(options.getKeyword())).append("\",\n");
        json.append("    \"mode\": \"").append(escapeJson(options.getMode())).append("\"\n");
        json.append("  },\n");
        json.append("  \"stats\": {\n");
        json.append("    \"duration_ms\": ").append(durationMs).append(",\n");
        json.append("    \"files_scanned\": ").append(filesScanned).append(",\n");
        json.append("    \"files_skipped\": ").append(filesSkipped).append(",\n");
        json.append("    \"files_with_matches\": ").append(results.size()).append(",\n");
        json.append("    \"total_matches\": ").append(totalMatches).append("\n");
        json.append("  },\n");
        json.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            FileScanResult file = results.get(i);
            boolean hasPages = file.matches().stream().anyMatch(m -> m.page() > 0);
            json.append("    {\n");
            json.append("      \"file\": \"").append(escapeJson(file.path())).append("\",\n");
            json.append("      \"total_matches\": ").append(file.totalMatches()).append(",\n");
            json.append("      \"matches\": [\n");
            List<FileMatch> matches = file.matches();
            for (int j = 0; j < matches.size(); j++) {
                FileMatch m = matches.get(j);
                json.append("        {");
                if (hasPages) json.append(" \"page\": ").append(m.page()).append(",");
                json.append(" \"line\": ").append(m.line()).append(",");
                json.append(" \"count\": ").append(m.count()).append(",");
                json.append(" \"snippet\": \"").append(escapeJson(m.snippet())).append("\" }");
                if (j < matches.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("      ]\n");
            json.append("    }");
            if (i < results.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        System.out.println(json.toString());
    }

    public void printFileTextOutput(CliOptions options, List<FileMatch> matches, long durationMs) {
        int total = matches.stream().mapToInt(FileMatch::count).sum();
        boolean hasPages = matches.stream().anyMatch(m -> m.page() > 0);

        System.out.println("--- WebGrep Results ---");
        System.out.println("Duration: " + formatDuration(durationMs));
        System.out.println("File: " + options.getFile());
        System.out.println("Total matches found: " + total);

        if (matches.isEmpty()) return;

        System.out.println("\nMatches:");
        for (FileMatch m : matches) {
            String loc = hasPages
                    ? String.format("p.%d, l.%d", m.page(), m.line())
                    : String.format("l.%d", m.line());
            String countStr = m.count() == 1 ? "(1 match)" : "(" + m.count() + " matches)";
            System.out.printf("  %-16s  %s  \"%s\"%n", loc, countStr, m.snippet());
        }
    }

    public void printFileJsonOutput(CliOptions options, List<FileMatch> matches, long durationMs) {
        int total = matches.stream().mapToInt(FileMatch::count).sum();
        boolean hasPages = matches.stream().anyMatch(m -> m.page() > 0);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"query\": {\n");
        json.append("    \"file\": \"").append(escapeJson(options.getFile())).append("\",\n");
        json.append("    \"keyword\": \"").append(escapeJson(options.getKeyword())).append("\",\n");
        json.append("    \"mode\": \"").append(escapeJson(options.getMode())).append("\"\n");
        json.append("  },\n");
        json.append("  \"stats\": {\n");
        json.append("    \"duration_ms\": ").append(durationMs).append(",\n");
        json.append("    \"total_matches\": ").append(total).append("\n");
        json.append("  },\n");
        json.append("  \"matches\": [\n");
        for (int i = 0; i < matches.size(); i++) {
            FileMatch m = matches.get(i);
            json.append("    {");
            if (hasPages) json.append(" \"page\": ").append(m.page()).append(",");
            json.append(" \"line\": ").append(m.line()).append(",");
            json.append(" \"count\": ").append(m.count()).append(",");
            json.append(" \"snippet\": \"").append(escapeJson(m.snippet())).append("\" }");
            if (i < matches.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        System.out.println(json.toString());
    }

    private String errorLabel(CrawlResult.ErrorType type) {
        return switch (type) {
            case NETWORK_ERROR -> "Network errors";
            case BLOCKED       -> "Blocked";
            case PARSE_ERROR   -> "Parse errors";
            case SKIPPED_SIZE  -> "Skipped (size limit)";
            case SKIPPED_TYPE  -> "Skipped (type filter)";
        };
    }

    private String formatDuration(long ms) {
        if (ms < 60_000) {
            return String.format("%.2fs", ms / 1000.0);
        }
        long minutes = ms / 60_000;
        double seconds = (ms % 60_000) / 1000.0;
        return String.format("%dm %.2fs", minutes, seconds);
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
