package com.webgrep.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates all command-line options accepted by WebGrep.
 *
 * <p>Supports three mutually exclusive input modes:
 * <ul>
 *   <li><b>Web crawl</b> — {@code --url} with optional depth, delay, and crawl controls</li>
 *   <li><b>Local file</b> — {@code --file} searches a single file offline</li>
 *   <li><b>Local folder</b> — {@code --folder} recursively searches all files in a directory</li>
 * </ul>
 *
 * <p>Use {@link #parse(String[])} to build an instance from raw CLI arguments,
 * then call {@link #validate()} to enforce required fields and mutual-exclusion rules
 * before passing the options to other components.
 */
public class CliOptions {

    /**
     * The complete set of recognised long-flag names (without the {@code --} prefix).
     * Any flag not in this set causes an {@link IllegalArgumentException} in {@link #parse},
     * so typos are caught immediately at startup.
     */
    private static final Set<String> KNOWN_FLAGS = Set.of(
        "url", "file", "folder", "keyword", "depth", "mode", "max-pages", "max-bytes",
        "timeout-ms", "delay-ms", "max-hits", "allow-external", "insecure", "all-urls",
        "dfs", "output", "help", "install-browser", "browser"
    );

    /** Starting URL for web crawl mode. {@code null} when not in web crawl mode. */
    private String url;

    /** Local file path for single-file mode. {@code null} when not in file mode. */
    private String file;

    /** Local directory path for folder-scan mode. {@code null} when not in folder mode. */
    private String folder;

    /** The keyword to search for. Required in all modes (except {@code --help} and {@code --install-browser}). */
    private String keyword;

    /** Maximum crawl depth. 0 = seed URL only; 1 = seed + all directly linked pages. Default: 1. */
    private int depth = 1;

    /** Matching strategy. One of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}. Default: {@code "default"}. */
    private String mode = "default";

    /** Maximum number of pages to visit in a web crawl before stopping. Default: 5000. */
    private int maxPages = 5000;

    /** Maximum response or file size in bytes. Responses larger than this are skipped. Default: 10 MB. */
    private long maxBytes = 10 * 1024 * 1024;

    /** Per-request network timeout in milliseconds. Default: 20 000 ms (20 seconds). */
    private int timeoutMs = 20000;

    /** Whether to follow links that lead to a different domain than the seed URL. Default: {@code false}. */
    private boolean allowExternal = false;

    /** Whether to skip SSL certificate verification. Should only be used for internal/test sites. Default: {@code false}. */
    private boolean insecure = false;

    /** When {@code true}, disables smart URL deduplication and visits every URL regardless of query parameters. Default: {@code false}. */
    private boolean allUrls = false;

    /** When {@code true}, uses depth-first search instead of breadth-first search. Default: {@code false} (BFS). */
    private boolean dfs = false;

    /** Stop after this many total keyword matches. {@code 0} means no limit. Default: 0. */
    private int maxHits = 0;

    /** Output format. One of {@code "text"} or {@code "json"}. Default: {@code "text"}. */
    private String output = "text";

    /** Delay between consecutive HTTP requests in milliseconds. Default: 100 ms. */
    private int delayMs = 100;

    /** Whether the user passed {@code --help} or {@code -h}. When {@code true}, no other processing is done. */
    private boolean help = false;

    /** Whether the user passed {@code --install-browser}. When {@code true}, only browser installation runs. */
    private boolean installBrowser = false;

    /**
     * Browser preference for SPA rendering. One of {@code "auto"} (try all tiers in order),
     * {@code "firefox"}, or {@code "chromium"}. Default: {@code "auto"}.
     */
    private String browser = "auto";

    /**
     * Parses raw command-line arguments into a {@code CliOptions} instance.
     *
     * <p>Both long flags ({@code --url https://…}) and short flags ({@code -u https://…}) are
     * supported. Boolean flags (e.g. {@code --allow-external}) take no value token; all other
     * flags consume the next token. The special case {@code -\d.*} (a next token that looks
     * like a negative number, e.g. {@code -100}) is allowed as a value even though it starts
     * with {@code -}.
     *
     * <p>Returns immediately (without parsing the rest of the args) after seeing {@code --help}
     * or {@code --install-browser}, since those flags cause an early exit.
     *
     * @param args the raw argument array from {@code main(String[])}.
     * @return a fully populated {@code CliOptions}; call {@link #validate()} before use.
     * @throws IllegalArgumentException if an unknown flag or an invalid numeric value is found,
     *                                  or if a value-flag is the last argument with no value after it.
     */
    public static CliOptions parse(String[] args) {
        CliOptions options = new CliOptions();
        Map<String, String> params = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key = null;
            boolean hasValue = false;

            if (arg.startsWith("--")) {
                key = arg.substring(2);
                if (!KNOWN_FLAGS.contains(key))
                    throw new IllegalArgumentException("Unknown option: --" + key);
                hasValue = isValuedFlag(key);
            } else if (arg.startsWith("-") && arg.length() == 2) {
                char shortFlag = arg.charAt(1);
                key = mapShortFlag(shortFlag);
                if (key == null)
                    throw new IllegalArgumentException("Unknown option: -" + shortFlag);
                hasValue = isValuedFlag(key);
            }

            if (key != null) {
                if (hasValue) {
                    if (i + 1 < args.length && (!args[i + 1].startsWith("-") || args[i + 1].matches("-\\d.*"))) {
                        params.put(key, args[i + 1]);
                        i++;
                    } else {
                        throw new IllegalArgumentException("Option --" + key + " requires a value but none was given");
                    }
                } else {
                    params.put(key, "true");
                }
            }
        }

        if (params.containsKey("help")) {
            options.help = true;
            return options;
        }

        // Parse --browser before any early-return so it's available alongside --install-browser.
        options.browser = params.getOrDefault("browser", "auto").toLowerCase();
        if (!options.browser.equals("auto") && !options.browser.equals("firefox") && !options.browser.equals("chromium"))
            throw new IllegalArgumentException("Invalid browser: " + options.browser + ". Use auto, firefox, or chromium.");

        if (params.containsKey("install-browser")) {
            options.installBrowser = true;
            return options;
        }

        options.url = params.get("url");
        options.file = params.get("file");
        options.folder = params.get("folder");
        options.keyword = params.get("keyword");

        try {
            if (params.containsKey("depth")) options.depth = Integer.parseInt(params.get("depth"));
            if (params.containsKey("max-pages")) options.maxPages = Integer.parseInt(params.get("max-pages"));
            if (params.containsKey("max-bytes")) options.maxBytes = Long.parseLong(params.get("max-bytes"));
            if (params.containsKey("timeout-ms")) options.timeoutMs = Integer.parseInt(params.get("timeout-ms"));
            if (params.containsKey("delay-ms")) options.delayMs = Integer.parseInt(params.get("delay-ms"));
            if (params.containsKey("max-hits")) options.maxHits = Integer.parseInt(params.get("max-hits"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in arguments: " + e.getMessage());
        }

        options.mode = params.getOrDefault("mode", "default").toLowerCase();
        options.allowExternal = params.containsKey("allow-external");
        options.insecure = params.containsKey("insecure");
        options.allUrls = params.containsKey("all-urls");
        options.dfs = params.containsKey("dfs");
        options.output = params.getOrDefault("output", "text").toLowerCase();

        return options;
    }

    /**
     * Returns {@code true} if the given flag name expects a value token to follow it.
     *
     * <p>Boolean flags ({@code allow-external}, {@code insecure}, {@code all-urls}, {@code dfs},
     * {@code help}, {@code install-browser}) are the only valueless flags; everything else
     * (url, keyword, depth, mode, browser, …) takes a value.
     *
     * @param key the canonical flag name (without {@code --}).
     * @return {@code true} if the flag takes a value; {@code false} if it is a boolean flag.
     */
    private static boolean isValuedFlag(String key) {
        if (key == null) return false;
        // Boolean flags take no value; all others (url, keyword, depth, mode, browser, …) do.
        return !key.equals("allow-external") && !key.equals("insecure") && !key.equals("all-urls")
            && !key.equals("dfs") && !key.equals("help") && !key.equals("install-browser");
    }

    /**
     * Maps a single-character short flag to its canonical long-flag name.
     *
     * @param c the character immediately after {@code -} in the argument.
     * @return the corresponding long-flag name, or {@code null} if the character is not recognised.
     */
    private static String mapShortFlag(char c) {
        return switch (c) {
            case 'u' -> "url";
            case 'f' -> "file";
            case 'F' -> "folder";
            case 'k' -> "keyword";
            case 'd' -> "depth";
            case 'm' -> "mode";
            case 'p' -> "max-pages";
            case 'b' -> "max-bytes";
            case 't' -> "timeout-ms";
            case 'e' -> "allow-external";
            case 'i' -> "insecure";
            case 'a' -> "all-urls";
            case 'o' -> "output";
            case 'r' -> "delay-ms";
            case 's' -> "dfs";
            case 'n' -> "max-hits";
            case 'h' -> "help";
            default -> null;
        };
    }

    /**
     * Validates the parsed options and throws {@link IllegalArgumentException} if any
     * constraint is violated.
     *
     * <p>Enforced constraints:
     * <ul>
     *   <li>Exactly one of {@code --url}, {@code --file}, {@code --folder} must be provided.</li>
     *   <li>If {@code --url} is given, its scheme must be {@code http} or {@code https}.</li>
     *   <li>{@code --keyword} is required and must not be blank.</li>
     *   <li>Numeric options ({@code depth}, {@code max-pages}, {@code max-bytes},
     *       {@code timeout-ms}, {@code delay-ms}, {@code max-hits}) must be in valid ranges.</li>
     *   <li>{@code --mode} must be one of {@code default}, {@code exact}, {@code fuzzy}.</li>
     *   <li>{@code --output} must be one of {@code text}, {@code json}.</li>
     * </ul>
     *
     * <p>Validation is skipped entirely when {@link #isHelp()} or {@link #isInstallBrowser()}
     * is {@code true}, since those modes exit before any crawl begins.
     *
     * @throws IllegalArgumentException if any constraint is violated.
     */
    public void validate() {
        if (help || installBrowser) return;
        int inputCount = (url != null ? 1 : 0) + (file != null ? 1 : 0) + (folder != null ? 1 : 0);
        if (inputCount > 1)
            throw new IllegalArgumentException("--url, --file, and --folder are mutually exclusive — specify only one");
        if (inputCount == 0)
            throw new IllegalArgumentException("Specify a target: --url <URL>, --file <path>, or --folder <path>");
        if (url != null) {
            // Reject any explicit scheme that is not http(s): covers both "ftp://x" and "javascript:x"
            if (url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")
                    && !url.startsWith("http://") && !url.startsWith("https://"))
                throw new IllegalArgumentException("URL scheme must be http or https");
        }
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("Keyword is required (-k, --keyword)");
        if (depth < 0) throw new IllegalArgumentException("Depth must be non-negative");
        if (maxPages <= 0) throw new IllegalArgumentException("Max pages must be greater than zero");
        if (maxBytes <= 0) throw new IllegalArgumentException("Max bytes must be greater than zero");
        if (timeoutMs < 0) throw new IllegalArgumentException("Timeout must be non-negative");
        if (delayMs < 0) throw new IllegalArgumentException("Delay must be non-negative");
        if (maxHits < 0) throw new IllegalArgumentException("Max hits must be non-negative (0 = no limit)");
        if (!mode.equals("default") && !mode.equals("exact") && !mode.equals("fuzzy")) {
            throw new IllegalArgumentException("Invalid mode: " + mode + ". Use default, exact, or fuzzy.");
        }
        if (!output.equals("text") && !output.equals("json")) {
            throw new IllegalArgumentException("Invalid output format: " + output + ". Use text or json.");
        }
    }

    /**
     * Prints the full usage and options help to {@code System.out}.
     * Called when {@code --help} or {@code -h} is passed, or when no arguments are given.
     */
    public static void printHelp() {
        System.out.println("WebGrep - Keyword search across websites, local files, and folders");
        System.out.println("\nUsage: java -jar WebGrep.jar <-u URL | -f path | -F path> -k <keyword> [options]");
        System.out.println("  -u <URL>   -k <keyword> [options]   web crawl mode");
        System.out.println("  -f <path>  -k <keyword> [options]   local file mode");
        System.out.println("  -F <path>  -k <keyword> [options]   local folder mode");
        System.out.println("\nOptions:");
        System.out.println("  -u, --url <URL>          The starting URL (required, unless --file or --folder is used)");
        System.out.println("  -f, --file <path>        Search a local file instead of crawling the web");
        System.out.println("  -F, --folder <path>      Search all files in a local folder (recursive)");
        System.out.println("  -k, --keyword <word>     The keyword to search for (required)");
        System.out.println("  -d, --depth <n>          Maximum crawl depth (default: 1)");
        System.out.println("  -m, --mode <mode>        Match mode: default, exact, or fuzzy");
        System.out.println("  -p, --max-pages <n>      Maximum number of pages to crawl (default: 5000)");
        System.out.println("  -b, --max-bytes <n>      Maximum file size in bytes (default: 10MB)");
        System.out.println("  -t, --timeout-ms <n>     Request timeout in milliseconds (default: 20000)");
        System.out.println("  -r, --delay-ms <n>       Delay between requests in milliseconds (default: 100)");
        System.out.println("  -a, --all-urls           Disable smart URL deduplication; visit every URL regardless of query parameters");
        System.out.println("  -n, --max-hits <n>       Stop after finding n total matches (default: 0 = no limit); applies to web crawl and folder scan");
        System.out.println("  -s, --dfs                Use depth-first search instead of breadth-first search");
        System.out.println("  -e, --allow-external     Allow crawling external domains");
        System.out.println("  -i, --insecure           Trust all SSL certificates (dangerous)");
        System.out.println("  -o, --output <format>    Output format: text (default) or json");
        System.out.println("      --browser <type>     Browser for SPA rendering: auto (default), firefox, or chromium");
        System.out.println("      --install-browser    Install a browser for SPA rendering and exit (uses --browser preference)");
        System.out.println("  -h, --help               Show this help message");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** @return the seed URL for web crawl mode, or {@code null} if not set. */
    public String getUrl() { return url; }

    /** @return the local file path for file mode, or {@code null} if not set. */
    public String getFile() { return file; }

    /** @return the local directory path for folder mode, or {@code null} if not set. */
    public String getFolder() { return folder; }

    /** @return the keyword to search for. */
    public String getKeyword() { return keyword; }

    /** @return the maximum crawl depth (default 1). */
    public int getDepth() { return depth; }

    /** @return the matching mode: {@code "default"}, {@code "exact"}, or {@code "fuzzy"}. */
    public String getMode() { return mode; }

    /** @return the maximum number of pages to visit (default 5000). */
    public int getMaxPages() { return maxPages; }

    /** @return the maximum response / file size in bytes (default 10 MB). */
    public long getMaxBytes() { return maxBytes; }

    /** @return the per-request network timeout in milliseconds (default 20 000). */
    public int getTimeoutMs() { return timeoutMs; }

    /** @return {@code true} if external-domain links should be followed. */
    public boolean isAllowExternal() { return allowExternal; }

    /** @return {@code true} if SSL certificate verification is disabled. */
    public boolean isInsecure() { return insecure; }

    /** @return {@code true} if smart URL deduplication is disabled. */
    public boolean isAllUrls() { return allUrls; }

    /** @return {@code true} if depth-first search is used instead of breadth-first. */
    public boolean isDfs() { return dfs; }

    /** @return the maximum total matches before stopping; {@code 0} means no limit. */
    public int getMaxHits() { return maxHits; }

    /** @return the output format: {@code "text"} or {@code "json"}. */
    public String getOutput() { return output; }

    /** @return the delay between requests in milliseconds (default 100). */
    public int getDelayMs() { return delayMs; }

    /** @return {@code true} if {@code --help} or {@code -h} was passed. */
    public boolean isHelp() { return help; }

    /** @return {@code true} if {@code --install-browser} was passed. */
    public boolean isInstallBrowser() { return installBrowser; }

    /** @return the browser preference: {@code "auto"}, {@code "firefox"}, or {@code "chromium"}. */
    public String getBrowser() { return browser; }
}
