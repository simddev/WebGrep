package com.webgrep.config;

import java.util.HashMap;
import java.util.Map;

public class CliOptions {
    private String url;
    private String keyword;
    private int depth = 1;
    private String mode = "default";
    private int maxPages = 5000;
    private long maxBytes = 10 * 1024 * 1024; // 10MB
    private int timeoutMs = 20000;
    private boolean allowExternal = false;
    private boolean insecure = false;
    private boolean noQuery = false;
    private String output = "text";
    private int delayMs = 100;
    private boolean help = false;

    public static CliOptions parse(String[] args) {
        CliOptions options = new CliOptions();
        Map<String, String> params = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key = null;
            boolean hasValue = false;

            if (arg.startsWith("--")) {
                key = arg.substring(2);
                hasValue = isValuedFlag(key);
            } else if (arg.startsWith("-") && arg.length() == 2) {
                char shortFlag = arg.charAt(1);
                key = mapShortFlag(shortFlag);
                hasValue = isValuedFlag(key);
            }

            if (key != null) {
                if (hasValue && i + 1 < args.length && (!args[i + 1].startsWith("-") || args[i + 1].matches("-\\d.*"))) {
                    params.put(key, args[i + 1]);
                    i++;
                } else {
                    params.put(key, "true");
                }
            }
        }

        if (params.containsKey("help")) {
            options.help = true;
            return options;
        }

        options.url = params.get("url");
        options.keyword = params.get("keyword");

        try {
            if (params.containsKey("depth")) options.depth = Integer.parseInt(params.get("depth"));
            if (params.containsKey("max-pages")) options.maxPages = Integer.parseInt(params.get("max-pages"));
            if (params.containsKey("max-bytes")) options.maxBytes = Long.parseLong(params.get("max-bytes"));
            if (params.containsKey("timeout-ms")) options.timeoutMs = Integer.parseInt(params.get("timeout-ms"));
            if (params.containsKey("delay-ms")) options.delayMs = Integer.parseInt(params.get("delay-ms"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in arguments: " + e.getMessage());
        }

        options.mode = params.getOrDefault("mode", "default").toLowerCase();
        options.allowExternal = params.containsKey("allow-external");
        options.insecure = params.containsKey("insecure");
        options.noQuery = params.containsKey("no-query");
        options.output = params.getOrDefault("output", "text").toLowerCase();

        return options;
    }

    private static boolean isValuedFlag(String key) {
        if (key == null) return false;
        return !key.equals("allow-external") && !key.equals("insecure") && !key.equals("no-query") && !key.equals("help");
    }

    private static String mapShortFlag(char c) {
        return switch (c) {
            case 'u' -> "url";
            case 'k' -> "keyword";
            case 'd' -> "depth";
            case 'm' -> "mode";
            case 'p' -> "max-pages";
            case 'b' -> "max-bytes";
            case 't' -> "timeout-ms";
            case 'e' -> "allow-external";
            case 'i' -> "insecure";
            case 'q' -> "no-query";
            case 'o' -> "output";
            case 'r' -> "delay-ms";
            case 'h' -> "help";
            default -> null;
        };
    }

    public void validate() {
        if (help) return;
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("URL is required (-u, --url)");
        if (keyword == null || keyword.isEmpty()) throw new IllegalArgumentException("Keyword is required (-k, --keyword)");
        if (depth < 0) throw new IllegalArgumentException("Depth must be non-negative");
        if (maxPages <= 0) throw new IllegalArgumentException("Max pages must be greater than zero");
        if (maxBytes <= 0) throw new IllegalArgumentException("Max bytes must be greater than zero");
        if (timeoutMs < 0) throw new IllegalArgumentException("Timeout must be non-negative");
        if (delayMs < 0) throw new IllegalArgumentException("Delay must be non-negative");
        if (!mode.equals("default") && !mode.equals("exact") && !mode.equals("fuzzy")) {
            throw new IllegalArgumentException("Invalid mode: " + mode + ". Use default, exact, or fuzzy.");
        }
        if (!output.equals("text") && !output.equals("json")) {
            throw new IllegalArgumentException("Invalid output format: " + output + ". Use text or json.");
        }
    }

    public static void printHelp() {
        System.out.println("WebGrep - A high-performance web crawler and keyword searcher");
        System.out.println("\nUsage: java -jar WebGrep.jar -u <URL> -k <keyword> [options]");
        System.out.println("\nOptions:");
        System.out.println("  -u, --url <URL>          The starting URL (required)");
        System.out.println("  -k, --keyword <word>     The keyword to search for (required)");
        System.out.println("  -d, --depth <n>          Maximum crawl depth (default: 1)");
        System.out.println("  -m, --mode <mode>        Match mode: default, exact, or fuzzy");
        System.out.println("  -p, --max-pages <n>      Maximum number of pages to crawl (default: 5000)");
        System.out.println("  -b, --max-bytes <n>      Maximum file size in bytes (default: 10MB)");
        System.out.println("  -t, --timeout-ms <n>     Request timeout in milliseconds (default: 20000)");
        System.out.println("  -r, --delay-ms <n>       Delay between requests in milliseconds (default: 100)");
        System.out.println("  -q, --no-query           Deduplicate URLs by path only, ignoring query strings");
        System.out.println("  -e, --allow-external     Allow crawling external domains");
        System.out.println("  -i, --insecure           Trust all SSL certificates (dangerous)");
        System.out.println("  -o, --output <format>    Output format: text (default) or json");
        System.out.println("  -h, --help               Show this help message");
    }

    // Getters
    public String getUrl() { return url; }
    public String getKeyword() { return keyword; }
    public int getDepth() { return depth; }
    public String getMode() { return mode; }
    public int getMaxPages() { return maxPages; }
    public long getMaxBytes() { return maxBytes; }
    public int getTimeoutMs() { return timeoutMs; }
    public boolean isAllowExternal() { return allowExternal; }
    public boolean isInsecure() { return insecure; }
    public boolean isNoQuery() { return noQuery; }
    public String getOutput() { return output; }
    public int getDelayMs() { return delayMs; }
    public boolean isHelp() { return help; }
}
