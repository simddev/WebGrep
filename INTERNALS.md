# WebGrep — Code Internals

A detailed walkthrough of every source file: what it does, why it works the way it does, and how the pieces connect.

---

## Table of Contents

1. [High-level data flow](#1-high-level-data-flow)
2. [Package map](#2-package-map)
3. [Entry point — `Main.java`](#3-entry-point--mainjava)
4. [Configuration — `CliOptions.java`](#4-configuration--clioptionsjava)
5. [Core: `Crawler.java`](#5-core-crawlerjava)
6. [Core: `PlaywrightRenderer.java`](#6-core-playwrightrendererjava)
7. [Core: `BrowserFinder.java`](#7-core-browserfinderjava)
8. [Core: `ContentExtractor.java`](#8-core-contentextractorjava)
9. [Core: `MatchEngine.java`](#9-core-matchenginejava)
10. [Core: `UrlDeduplicator.java`](#10-core-urldeduplicatorjava)
11. [Reporting: `CrawlResult.java`](#11-reporting-crawlresultjava)
12. [Reporting: `FileMatch.java` & `FileScanResult.java`](#12-reporting-filematchjava--filescanresultjava)
13. [Reporting: `ReportWriter.java`](#13-reporting-reportwriterjava)
14. [Utilities: `UrlUtils.java`](#14-utilities-urlutilsjava)
15. [Tests](#15-tests)
16. [Build & deployment](#16-build--deployment)

---

## 1. High-level data flow

```
args
  │
  ▼
CliOptions.parse()          ← validates all flags, detects mode
  │
  ├─ folder mode ──► Main.scanFolder()
  │                    └─ ContentExtractor.extractTextFromBinary()  ← Tika
  │                    └─ Main.findLineMatches()                    ← MatchEngine
  │                    └─ ReportWriter.printFolderText/JsonOutput()
  │
  ├─ file mode ────► Main.scanLocalFile()
  │                    └─ (same as folder, single file)
  │                    └─ ReportWriter.printFileText/JsonOutput()
  │
  └─ web crawl ────► Crawler.crawl()
                       └─ fetchWithRetry()                          ← Jsoup HTTP
                       └─ ContentExtractor.extractTextFromHtml()    ← Jsoup DOM
                          or ContentExtractor.extractTextFromBinary() ← Tika
                       └─ PlaywrightRenderer.render()  (SPA only)  ← Playwright
                       └─ MatchEngine.countMatches() / findSnippets()
                       └─ UrlDeduplicator.isDuplicate() / markQueued()
                       └─ CrawlResult (accumulates everything)
                       └─ ReportWriter.printText/JsonOutput()
```

Three modes share `ContentExtractor` and `MatchEngine`. Only the web crawl mode uses `Crawler`, `PlaywrightRenderer`, `BrowserFinder`, and `UrlDeduplicator`.

---

## 2. Package map

```
com.webgrep
├── Main.java                    ← entry point, mode routing
├── config/
│   └── CliOptions.java          ← CLI parsing & validation
├── core/
│   ├── BrowserFinder.java       ← cross-platform browser discovery
│   ├── ContentExtractor.java    ← HTML → text (Jsoup), binary → text (Tika)
│   ├── Crawler.java             ← BFS/DFS web crawl engine
│   ├── MatchEngine.java         ← keyword matching (default / exact / fuzzy)
│   ├── PlaywrightRenderer.java  ← headless browser for SPA pages
│   └── UrlDeduplicator.java     ← smart URL deduplication
├── reporting/
│   ├── CrawlResult.java         ← mutable accumulator for crawl statistics
│   ├── FileMatch.java           ← one matched line (record)
│   ├── FileScanResult.java      ← matches for one file (record)
│   └── ReportWriter.java        ← formats output as text or JSON
└── utils/
    └── UrlUtils.java            ← URL normalisation, link filtering
```

---

## 3. Entry point — `Main.java`

**File:** `src/main/java/com/webgrep/Main.java`

`Main` is the only class with a `main` method. It does four things:

### 3.1 Silence library logging

```java
private static void setupLogging() { … }
```

Tika, SLF4J, and Playwright all emit noisy logs to stderr by default. `setupLogging()` runs first and silences all of them so the user only sees WebGrep's own output.

### 3.2 Parse args and route to the right mode

```java
CliOptions options = CliOptions.parse(args);
```

After parsing, `Main` checks three mutually exclusive branches:

| Condition | Mode |
|---|---|
| `options.getFolder() != null` | folder scan — `scanFolder()` |
| `options.getFile() != null` | single file — `scanLocalFile()` |
| otherwise | web crawl — `new Crawler(…).crawl()` |

### 3.3 `scanFolder()`

Walks the directory tree with `Files.walk()`, sorts the paths, then for each file:
- Skips it if it exceeds `--max-bytes` (counted as `skipped`)
- Reads the raw bytes and calls `ContentExtractor.extractTextFromBinary()` (Tika handles all formats)
- Calls `findLineMatches()` to search the extracted text
- Accumulates into a `List<FileScanResult>`
- Stops early if `--max-hits` is reached

A spinner-style progress indicator is printed to stderr during the scan and erased when done.

### 3.4 `findLineMatches()`

This is the core local-search loop used by both `scanFolder()` and `scanLocalFile()`:

```java
static List<FileMatch> findLineMatches(String text, String keyword, String mode, MatchEngine matchEngine)
```

1. Normalises line endings (`\r\n` → `\n`, `\r` → `\n`).
2. Splits on `\f` (form-feed) to separate pages. Apache Tika uses `\f` as the page separator for multi-page documents (PDFs). If no `\f` is found, the document is treated as having no page structure and `page = 0` is recorded.
3. For each line on each page, calls `MatchEngine.countMatches()`. If the count is > 0, creates a `FileMatch` with the page number, line number, match count, and a 120-character snippet.

### 3.5 `installBrowser()`

Handles the `--install-browser` flag. Checks for a usable browser in priority order and reports it without downloading if one is found:

1. **System Chromium/Chrome** — trusted unconditionally (CDP-native).
2. **Playwright-cached Firefox** (`~/.cache/ms-playwright/firefox-*`) — previously downloaded, always compatible.
3. **Playwright-cached Chromium** (`~/.cache/ms-playwright/chromium-*`) — same as above.
4. **System Firefox stable** — trusted unless the path contains `developer-edition` or `nightly`. Dev Edition and Nightly are incompatible with Playwright's patched protocol and are skipped so the user is prompted to download a compatible build.

If no compatible browser is found, the user is prompted to choose Firefox or Chromium (or `--browser` preference is used), and `com.microsoft.playwright.CLI.main(new String[]{"install", …})` — the official Playwright CLI installer — downloads and installs it.

### 3.6 Error handling

Two catch levels:
- `IllegalArgumentException` → configuration error (bad flags, missing required args) → exits with code 1
- `Exception` → fatal runtime error → prints the exception chain and exits with code 2

---

## 4. Configuration — `CliOptions.java`

**File:** `src/main/java/com/webgrep/config/CliOptions.java`

`CliOptions` is a pure data class that parses raw `String[] args` and validates the result. No logic beyond parsing and validation — it does not execute anything.

### 4.1 How parsing works

`parse()` iterates through `args` one token at a time:
- Long flags (`--depth 2`) are recognised by the `KNOWN_FLAGS` set. Unknown flags throw `IllegalArgumentException` immediately so typos are caught at startup.
- Short flags (`-d 2`) are resolved through `mapShortFlag(char)`, a switch expression that maps single characters to canonical flag names.
- Boolean flags (e.g. `--allow-external`, `--dfs`) call `params.put(key, "true")` and consume no next token.
- Value flags (everything else) consume the next token as the value, except when the next token looks like another flag (`startsWith("-")` but is not a negative number).

Negative number detection: the condition `args[i+1].matches("-\\d.*")` allows values like `-r -500` (negative delay) to be parsed correctly without being mistaken for a flag.

### 4.2 Defaults

| Field | Default |
|---|---|
| `depth` | 1 |
| `maxPages` | 5000 |
| `maxBytes` | 10 MB |
| `timeoutMs` | 20000 ms |
| `delayMs` | 100 ms |
| `mode` | "default" |
| `output` | "text" |
| `browser` | "auto" |
| `allowExternal` | false |
| `insecure` | false |
| `allUrls` | false |
| `dfs` | false |
| `maxHits` | 0 (no limit) |

### 4.3 `validate()`

Called after `parse()` in `Main`. Enforces:
- Exactly one of `--url`, `--file`, `--folder` is set.
- `--url` scheme must be `http` or `https`.
- `--keyword` is required and non-blank.
- All numeric options are in valid ranges.
- `--mode` is one of `default`, `exact`, `fuzzy`.
- `--output` is one of `text`, `json`.

`--help` and `--install-browser` skip validation entirely (they exit before a full crawl is needed).

### 4.4 Short flag map

| Short | Long |
|---|---|
| `-u` | `--url` |
| `-f` | `--file` |
| `-F` | `--folder` |
| `-k` | `--keyword` |
| `-d` | `--depth` |
| `-m` | `--mode` |
| `-p` | `--max-pages` |
| `-b` | `--max-bytes` |
| `-t` | `--timeout-ms` |
| `-r` | `--delay-ms` |
| `-n` | `--max-hits` |
| `-e` | `--allow-external` |
| `-i` | `--insecure` |
| `-a` | `--all-urls` |
| `-s` | `--dfs` |
| `-o` | `--output` |
| `-h` | `--help` |

---

## 5. Core: `Crawler.java`

**File:** `src/main/java/com/webgrep/core/Crawler.java`

`Crawler` is the most complex class. It implements a configurable BFS/DFS web crawl with SPA detection, cookie management, and retry logic.

### 5.1 State

```java
private final Map<String, Map<String, String>> cookieJar;   // host → {name → value}
private Boolean spaRenderingEnabled;   // null=not asked, true/false=decided
private String startDomain;
private boolean allowSubdomains;
private final UrlDeduplicator dedup;
private final SSLSocketFactory insecureSslFactory;
```

The cookie jar is a two-level map scoped by host so that cookies from site A are never sent to site B when `--allow-external` is used.

`spaRenderingEnabled` is a three-state `Boolean` (boxed):
- `null` — the user hasn't been asked yet
- `true` — user said yes (or non-interactive mode)
- `false` — user said no

### 5.2 Domain scoping

```java
this.startDomain = seedHasWww ? startHost.substring(4) : startHost;
this.allowSubdomains = seedHasWww;
```

If the seed URL is `www.example.com`, WebGrep strips `www.` to get the root domain `example.com` and sets `allowSubdomains = true`. This means `docs.example.com`, `en.example.com`, etc. are all followed. If the seed is `docs.example.com` (no `www.`), only the exact domain and its `www.` alias are allowed.

`isSameDomain(linkHost)` implements this check. If the seed permanently redirects to a different domain (e.g. an old domain that migrated), the domain scope is updated on the very first page visit so links on the new domain are followed.

### 5.3 The crawl loop

```java
public CrawlResult crawl() {
    Deque<UrlDepth> queue = new LinkedList<>();
    queue.addLast(new UrlDepth(normalizedStart, 0));
    …
    while (!queue.isEmpty() && crawlResult.visitedCount < options.getMaxPages()) {
        UrlDepth current = queue.pollFirst();
        …
    }
}
```

`UrlDepth` is a simple record: `(String url, int depth)`.

`Deque<UrlDepth>` is used as both a queue and a stack:
- **BFS (default):** `queue.addLast()` → FIFO order, levels processed left-to-right.
- **DFS (`--dfs`):** `queue.addFirst()` → LIFO order, follows each link chain as deep as possible before backtracking.

### 5.4 Processing each page

For each URL popped from the queue:

1. **Fetch** via `fetchWithRetry()`.
2. **Store cookies** from the response.
3. **Track effective URL** — Jsoup follows redirects; the final URL after redirects is the canonical URL for this page. It is marked as queued so no other link to it triggers a duplicate visit.
4. **Check Content-Type:**
   - `text/html` or `application/xhtml+xml` → HTML path
   - Anything else → binary path (Tika)

**HTML path:**
- Jsoup parses the response into a `Document`.
- Cloudflare/bot-protection check: if the page title contains "Just a moment..." or body text contains "Enable JavaScript and cookies to continue", the URL is added to `blockedUrls`.
- `ContentExtractor.extractTextFromHtml(doc)` extracts title + body text + meta tags.
- `ContentExtractor.extractLinks(doc, …)` extracts all `<a href>` links and splits them into:
  - `docLinksToEnqueue` — links whose path ends in a known document extension (PDF, DOCX, etc.)
  - `navLinks` — all other links (HTML pages, unknown paths)
- **SPA detection:** calls `PlaywrightRenderer.isSpa(doc)`. If true, the user is prompted (once) and `PlaywrightRenderer.render()` is called. The rendered result replaces the Jsoup-extracted text and links.

**Binary path:**
- `ContentExtractor.extractTextFromBinary()` passes the bytes to Tika.
- `crawlResult.docsCount++` increments the document counter.

**Matching:**
- `MatchEngine.countMatches()` counts occurrences in the extracted text.
- `MatchEngine.findSnippets()` extracts up to 3 context snippets around each match.
- Results are recorded via `crawlResult.addMatch()` and `crawlResult.addSnippets()`.

**Link enqueuing — the key distinction:**
```java
// Document links bypass the depth limit
for (String docLink : docLinksToEnqueue) {
    queue.addLast(new UrlDepth(docLink, current.depth + 1));
}

// Navigation links only enqueued within depth limit
if (current.depth < options.getDepth()) {
    for (String link : navLinks) {
        queue.addFirst/Last(new UrlDepth(link, current.depth + 1));
    }
}
```

The reason document links bypass the depth check: documents are leaf nodes — they don't link to other pages, so enqueuing them can never cause the crawl to expand exponentially beyond the intended boundary. A PDF linked from a depth-1 page should be fetched even if `--depth 1` would otherwise stop there.

Navigation links are capped at the depth limit to prevent unbounded crawls.

### 5.5 `fetchWithRetry()`

```java
private org.jsoup.Connection.Response fetchWithRetry(String url) throws Exception {
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        …
        if (status == HTTP_TOO_MANY_REQUESTS && attempt < MAX_RETRIES) {
            long waitMs = parseRetryAfter(response.header("Retry-After"), 1000L << attempt);
            Thread.sleep(waitMs);
        } else if (status >= 400) {
            throw new org.jsoup.HttpStatusException(…);
        } else {
            return response;
        }
    }
}
```

On HTTP 429, waits using exponential backoff (2s, 4s) or the value from the `Retry-After` header, capped at 60 seconds. Non-429 4xx and 5xx errors are thrown immediately as `HttpStatusException`.

The `User-Agent` header mimics Chrome on Linux, which gets through more WAFs than an obvious bot string.

### 5.6 SSL / insecure mode

When `--insecure` is passed, `buildInsecureSslFactory()` creates a `TrustManager` that accepts all certificates and all hostnames. This involves a global `HttpsURLConnection.setDefaultHostnameVerifier()` call (Jsoup has no per-connection API for this). The original verifier is saved and restored after the crawl in the `finally` block.

### 5.7 Progress display

```java
private void printProgress(String currentUrl, int visited, int matches) {
    System.err.printf("\r%-110s", line);
}
```

Uses `\r` (carriage return) to overwrite the same line in the terminal. Printed to `stderr` so it doesn't interfere with JSON output on `stdout`.

---

## 6. Core: `PlaywrightRenderer.java`

**File:** `src/main/java/com/webgrep/core/PlaywrightRenderer.java`

`PlaywrightRenderer` manages a headless browser session for rendering SPA pages. It implements `AutoCloseable` so the browser is properly shut down when the crawl ends.

### 6.1 The `RenderedPage` record

```java
public record RenderedPage(String text, List<String> links, List<String> docLinks, Map<String, String> cookies) {}
```

- `text` — visible text extracted from the rendered DOM
- `links` — all `<a href>` URLs found in the rendered page (fully resolved via `a.href`)
- `docLinks` — document download URLs found in intercepted JSON responses + `<a download>` links
- `cookies` — cookies set by the browser session, to be returned to the Crawler's cookie jar

### 6.2 SPA detection — `isSpa(doc)`

```java
public static boolean isSpa(Document doc) { … }
```

Called by `Crawler` on the Jsoup-parsed HTML before any browser is started. Returns `true` if any of these signals are present:
- `<html ng-version="…">` — Angular
- `<html data-beasties-container>` — Angular SSR (static shell)
- `<html data-n-head>` — Nuxt.js
- `[data-reactroot]` — React
- `<script id="__NEXT_DATA__">` — Next.js
- `<app-root>` with < 100 characters of text — Angular (empty shell)
- `<div id="root">` or `<div id="app">` with < 100 characters — React/Vue (empty shell)
- Body text < 100 chars + any JS bundles — generic SPA shell
- Body text < 300 chars + 2 or more JS bundles — stronger generic SPA signal

### 6.3 Browser resolution — `ensureReady()`

Called every time `render()` is invoked. Tries five tiers in order:

| Tier | What happens |
|---|---|
| 1. System Chromium/Chrome | Found via `BrowserFinder.findChromium()` → launched with `executablePath` |
| 2. System Firefox | Found via `BrowserFinder.findFirefox()` → best-effort; falls through if Dev/Nightly is incompatible with Playwright |
| 3. Playwright's cached Firefox | `~/.cache/ms-playwright/firefox-*` exists |
| 4. Playwright's cached Chromium | `~/.cache/ms-playwright/chromium-*` exists |
| 5. Download | User is prompted (or `--browser` preference is used); browser is downloaded via subprocess |

`--browser firefox` skips tier 1 (Chromium). `--browser chromium` skips tiers 2 and 3 (Firefox options).

In non-interactive mode (`System.console() == null`), tier 5 throws an `IOException` instead of prompting, which marks the renderer `unavailable` and prints a warning.

**Why tier 5 uses a subprocess:** `com.microsoft.playwright.CLI.main()` calls `System.exit(0)` when the download completes. Calling it in-process during an active crawl would kill the JVM and discard all results collected so far. Instead, `downloadAndLaunch()` spawns a child process via `ProcessBuilder` to run the installer. The parent JVM waits for the child to finish, then launches the browser normally.

`initPlaywright()` sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` in the environment to prevent Playwright from downloading any browser during `Playwright.create()` — only an explicit subprocess `CLI install …` should download anything.

### 6.4 Persistent browser context

```java
private BrowserContext persistentContext;
private Page persistentPage;
private String persistentBaseUrl;
```

The browser context and page are kept alive across multiple calls to `render()` as long as they are on the same domain (same `baseUrl`). This is a major performance optimisation for SPAs:
- On the **first navigation** to a domain, the JavaScript bundle must be downloaded, parsed, and executed. This can take 10–15 seconds.
- On **subsequent navigations** to the same domain (e.g. different routes of the same Angular app), the router just swaps components. The bundle is already loaded, so navigation typically takes 150–400 ms.

A new context is created if:
- `persistentPage` is null or closed.
- The domain of the new URL is different from `persistentBaseUrl`.

### 6.5 Resource blocking

```java
persistentContext.route("**/*", route -> {
    String rt = route.request().resourceType();
    if ("document".equals(rt) || "script".equals(rt) || "xhr".equals(rt) || "fetch".equals(rt)) {
        route.resume();
    } else {
        route.abort();
    }
});
```

Images, fonts, stylesheets, and media are blocked. This speeds up page load significantly and avoids triggering `NETWORKIDLE` delays caused by large media assets.

### 6.6 Wait strategy

Two different wait strategies depending on whether this is the first navigation to the domain:

**First navigation:**
```java
persistentPage.waitForLoadState(LoadState.DOMCONTENTLOADED, …);
persistentPage.waitForLoadState(LoadState.NETWORKIDLE, …);
```
Both `DOMContentLoaded` and `NETWORKIDLE` are awaited so that the initial API response (which often contains the full content list) is captured by the response interceptor.

**Subsequent navigations:**
```java
persistentPage.waitForFunction(
    "() => { const r = document.querySelector('main, …') || document.body; "
    + "return r && r.innerText.trim().length > 100; }",
    null, new Page.WaitForFunctionOptions().setPollingInterval(50));
```
Instead of waiting for `NETWORKIDLE` (which can take several seconds), WebGrep polls every 50 ms for the content area to contain at least 100 characters of text. This resolves much faster (typically in 150–400 ms).

### 6.7 DOM snapshot — why `a.href` instead of `a.getAttribute('href')`

```javascript
const h = a.href;  // DOM property — fully resolved by browser
```

The key insight: the browser DOM property `a.href` always returns the **fully resolved absolute URL**. The browser applies the page's `<base href>` tag automatically.

The HTML attribute `a.getAttribute('href')` returns the **raw string from the HTML source**, which may be a bare relative path like `api/v1/files/123/download`.

Problem with `getAttribute`: Angular and other SPAs set `<base href="/app/">` in the HTML head. Relative links like `api/v1/…` are then supposed to be resolved against that base — so the correct URL is `https://example.com/app/api/v1/…`. But if you resolve them manually using `new URL(rawHref, pageURL)`, where `pageURL` is something like `https://example.com/app/uredni-deska/detail`, you get `https://example.com/app/uredni-deska/api/v1/…` — wrong path, 404.

Solution: use `a.href`, let the browser handle the `<base href>` resolution, and receive the already-correct absolute URL.

The `raw` variable from `a.getAttribute('href')` is still checked first — but only to filter out `javascript:`, `mailto:`, and pure fragment links (`#` or `#something`), which cannot be detected by checking `a.href` alone (the browser resolves `#` to the full current page URL).

### 6.8 JSON API interception — `captureDocLinks()`

```java
persistentPage.onResponse(
    response -> captureDocLinks(response, this.interceptorUrlBase, this.interceptedLinks));
```

Playwright fires an event for every HTTP response the browser receives. `captureDocLinks()` is the handler:

1. Only processes HTTP 200 responses with `Content-Type: application/json`.
2. Skips responses larger than 5 MB to avoid blocking the browser's network thread.
3. Applies two regex patterns to the response body:
   - `JSON_DOC_URL` — matches URLs ending in known document extensions (`.pdf`, `.docx`, `.xlsx`, etc.) inside JSON strings.
   - `JSON_DOWNLOAD_URL` — matches URLs whose final path segment is literally `/download` (common REST API pattern for file download endpoints like `/api/v1/files/123/download`).
4. Resolves relative URLs against the domain base and adds them to `interceptedLinks`.

`interceptedLinks` is a `Collections.synchronizedList` because the response handler runs on Playwright's internal network thread, not the main thread.

The `interceptorUrlBase` is the scheme + host of the current page (e.g. `https://example.com`). It is set before each `navigate()` call and used to prefix relative paths found in JSON.

### 6.9 Cookie round-trip

```java
private void passCookies(BrowserContext context, Map<String, String> cookies, String url) { … }
```

Before each navigation, cookies from the Crawler's jar (collected by Jsoup from plain HTTP responses) are passed into the Playwright browser context. This ensures pages that require session cookies (e.g. behind a login) render correctly.

After navigation, cookies that the browser set are extracted and returned in `RenderedPage.cookies()`. The Crawler stores these back into its jar for subsequent Jsoup requests to the same host.

Only cookies scoped to the page's own domain are extracted — third-party cookies set by analytics scripts are filtered out to avoid polluting the cookie jar for the wrong host.

---

## 7. Core: `BrowserFinder.java`

**File:** `src/main/java/com/webgrep/core/BrowserFinder.java`

Locates system-installed browser binaries without any external dependencies. Used by `PlaywrightRenderer.ensureReady()` (tiers 1 and 2).

### 7.1 Strategy

Two public methods: `findChromium()` and `findFirefox()`.

Each method:
1. Calls the corresponding `knownXxxPaths()` helper to get a list of hard-coded install paths for the detected OS (macOS / Windows / Linux).
2. Checks each path with `Files.isExecutable(p)`.
3. If no hard-coded path works, calls `findViaShell()` as a fallback.

### 7.2 `findViaShell(String... names)`

Runs `which <name>` (Linux/macOS) or `where <name>` (Windows) for each browser name in order. Parses the first line of output, checks that the result is executable, and returns it.

This handles non-standard install locations (e.g. browsers installed via Flatpak, AUR, nix, or a custom prefix).

### 7.3 Browser preference order

For Chromium/Chrome (checked before Firefox):
- Linux: `chromium-browser`, `chromium`, `google-chrome`, `google-chrome-stable`; hard-coded `/snap/bin/chromium`
- macOS: Chromium.app, Google Chrome.app
- Windows: `%LOCALAPPDATA%\Chromium`, `%LOCALAPPDATA%\Google\Chrome`, `%ProgramFiles%\Chromium`

For Firefox (checked second):
- Linux: `firefox-developer-edition`, `firefox-nightly`, `firefox`, `firefox-esr`, `iceweasel`; `/opt/firefox*` paths
- macOS: Firefox Developer Edition, Nightly, stable, ESR
- Windows: Program Files paths in the same order

Dev Edition and Nightly appear first in the path list because they are often installed alongside stable Firefox (at a distinct path) and should be attempted if available. However, they are *not* preferred over stable Firefox in the compatibility sense — if `PlaywrightRenderer` fails to launch them (Playwright's patched protocol is incompatible), the exception is caught and the tier falls through silently to the Playwright-cached builds.

---

## 8. Core: `ContentExtractor.java`

**File:** `src/main/java/com/webgrep/core/ContentExtractor.java`

Responsible for converting raw bytes (HTML or binary documents) into plain searchable text strings.

### 8.1 HTML extraction — `extractTextFromHtml(Document doc)`

Takes a Jsoup `Document` already parsed from HTML and returns a concatenation of:
- `doc.title()` — the `<title>` tag
- `doc.body().text()` — all visible text from the body, whitespace-collapsed by Jsoup
- `meta[name=description]` content
- `meta[name=keywords]` content

This single string is what `MatchEngine` searches.

### 8.2 Binary extraction — `extractTextFromBinary(byte[] body, String url, String contentType)`

Used for PDFs, DOCX files, and any other non-HTML response. The flow:

1. Creates a Tika `Metadata` object. Setting `RESOURCE_NAME_KEY` to the URL (or filename) lets Tika use the file extension as a hint for format detection.
2. Calls `tikaParseWithTimeout(body, metadata)`.
3. If that returns empty or null, retries without metadata (format detection from magic bytes only).
4. If Tika fails entirely, falls back to raw `UTF-8` interpretation of the bytes.

### 8.3 `tikaParseWithTimeout()`

```java
private String tikaParseWithTimeout(byte[] body, Metadata metadata) {
    Future<String> future = TIKA_EXECUTOR.submit(() -> tika.parseToString(is, metadata));
    try {
        return future.get(TIKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        future.cancel(true);
        return null;
    }
}
```

Tika can hang on corrupt or malformed files. Wrapping it in a `Future` with a 30-second timeout prevents one bad file from blocking the entire crawl or folder scan.

`TIKA_EXECUTOR` is a daemon thread pool — daemon threads don't prevent JVM shutdown if the main thread exits.

### 8.4 `tika.setMaxStringLength()`

```java
int tikaLimit = (int) Math.min(Math.max(maxBytes, 50L * 1024 * 1024), Integer.MAX_VALUE);
this.tika.setMaxStringLength(tikaLimit);
```

Tika's string output is capped independently of `--max-bytes`. The floor is 50 MB — even if the user sets a small `--max-bytes` to throttle download sizes, local documents in `--file` / `--folder` mode should not be silently truncated.

### 8.5 Link extraction — `extractLinks(Document doc, byte[] rawBody, String baseUrl)`

Called by `Crawler` for HTML pages to collect all `<a href>` links.

1. Uses Jsoup's `doc.select("a[href]")` and `element.absUrl("href")` to get absolute URLs (Jsoup resolves them against the document's own base URL).
2. Normalises each URL via `UrlUtils.normalizeUrl()` and filters via `UrlUtils.isIgnoredLink()`.
3. Uses `LinkedHashSet` to deduplicate while preserving insertion order.
4. Caps at 5000 links per page.

Fallback: if Jsoup found no `<a>` elements (the page might be raw HTML or a malformed document), a regex `href="…"` scan is applied to the raw bytes.

---

## 9. Core: `MatchEngine.java`

**File:** `src/main/java/com/webgrep/core/MatchEngine.java`

Implements three keyword matching strategies. All public methods accept `(text, keyword, mode)` and are stateless except for the compiled pattern cache.

### 9.1 Pattern cache

```java
private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();
```

Compiling a regex is expensive. The cache keyed by `keyword + "\0" + mode` ensures each keyword/mode pair is compiled only once, no matter how many pages are searched.

### 9.2 Default mode — case-insensitive with diacritic support

**Regex pass:**
```java
Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
```
`Pattern.quote(keyword)` escapes any regex metacharacters in the keyword so it is always treated as a literal string. `UNICODE_CASE` makes the case-insensitivity work correctly for non-ASCII letters (e.g. `Ü`, `Č`).

**Simplified pass (diacritic fallback):**
```java
String simpleKeyword = superSimplify(keyword);
String simpleText = superSimplify(text);
int simpleCount = …indexOf loop…;
count = Math.max(count, simpleCount);
```

`superSimplify()` strips all diacritics (NFD normalisation, remove combining marks), lowercases everything, and removes all non-alphanumeric characters. So `café` → `cafe`, `Tomáš` → `tomas`.

This means searching for `Tomas` will also find `Tomáš` in the text, and vice versa.

The simplified count and the regex count are both computed, and the higher value is returned. Why? A text like `"cafe Café"` with keyword `cafe` would yield 1 from regex (misses `Café`) but 2 from the simplified pass.

The guard `simpleKeyword.length() >= 2` prevents single-character matches — a keyword like `C++` simplifies to `c`, which would match nearly every word in the text.

### 9.3 Exact mode

Simple `Pattern.CASE_SENSITIVE` regex. `hello` matches `hello` but not `Hello` or `HELLO`.

### 9.4 Fuzzy mode

**First pass:** same simplified substring search as default mode. If this finds matches, returns the count.

**Second pass (Levenshtein):** if no substring match was found, splits the text into individual words and computes the Levenshtein edit distance from each word to the (simplified) keyword.

Threshold:
- Keyword length ≤ 4: edit distance ≤ 1 (one substitution/insertion/deletion)
- Keyword length > 4: edit distance ≤ 2

An early exit optimises away words whose length difference alone exceeds the threshold: if `|word.length - keyword.length| > threshold`, no edit sequence within the threshold can possibly make them match.

### 9.5 `findSnippets()`

Returns up to `maxSnippets` context strings — short excerpts from the text with the keyword in the middle.

1. Flattens whitespace: replaces tabs, newlines, and runs of spaces with single spaces, so the snippet fits neatly on one output line.
2. Runs the regex match (same pattern used by `countMatches`). For each match position, calls `buildSnippet()`.
3. If regex found nothing and mode is not `exact`, runs the simplified pass with a character position map to convert simplified-text positions back to original-text positions, so the snippet contains the original accented characters (not the stripped version).

`buildSnippet(flat, start, end)` takes 60 characters before and after the match, then extends both ends to word boundaries (so snippets don't cut mid-word).

---

## 10. Core: `UrlDeduplicator.java`

**File:** `src/main/java/com/webgrep/core/UrlDeduplicator.java`

Prevents the crawler from visiting the same page twice. Handles the tricky case of URLs with query parameters.

### 10.1 The problem

A naive `Set<String>` deduplicator would re-visit `?id=1&sort=asc` even if `?id=1` was already visited — they are different strings. But a smarter deduplicator would also wrongly suppress `?id=2` as a duplicate of `?id=1` — these are genuinely different pages.

### 10.2 Smart dedup rule (when `allUrls = false`)

When a URL is first queued, its query parameters are stored as the "canonical" set for that base path:

```
queue ?id=1           → canonicalPathParams["/list"] = {"id=1"}
queue ?id=1&sort=asc  → isDuplicate: new params {"id=1", "sort=asc"} ⊇ canonical {"id=1"} → DUPLICATE
queue ?id=2           → isDuplicate: new params {"id=2"} ⊉ canonical {"id=1"} → NOT DUPLICATE
```

The rule: a URL is a duplicate if its parameter set is a **superset** of the canonical set. This catches sort/filter variants (which add extra parameters on top of the same content ID) but allows genuinely different content (different ID values).

### 10.3 Scheme normalisation

```java
private static String schemeKey(String url) {
    return url.startsWith("http://") ? "https://" + url.substring(7) : url;
}
```

`http://` and `https://` variants of the same URL are always treated as duplicates. Only the first-encountered variant is fetched.

### 10.4 `--all-urls` mode

When `allUrls = true`, only exact-string duplicates are suppressed. Every unique URL string is visited, regardless of query parameter relationships.

---

## 11. Reporting: `CrawlResult.java`

**File:** `src/main/java/com/webgrep/reporting/CrawlResult.java`

A mutable data bag filled in by `Crawler` during the crawl and consumed by `ReportWriter` at the end.

### 11.1 Fields

| Field | Type | Purpose |
|---|---|---|
| `results` | `LinkedHashMap<String, Integer>` | URL → match count (insertion order preserved) |
| `snippets` | `LinkedHashMap<String, List<String>>` | URL → list of context snippets |
| `blockedUrls` | `LinkedHashMap<String, String>` | URL → reason (403, bot challenge, etc.) |
| `errorCounts` | `LinkedHashMap<ErrorType, Integer>` | counts per error category |
| `networkErrorReasons` | `LinkedHashMap<String, Integer>` | breakdown of network error types |
| `visitedCount` | `int` | total pages fetched (all content types) |
| `parsedCount` | `int` | pages successfully parsed (HTML + binary) |
| `docsCount` | `int` | binary documents (PDF, DOCX, etc.) parsed |
| `durationMs` | `long` | wall-clock time for the entire crawl |
| `stoppedAtMaxHits` | `int` | 0 if ran to completion; otherwise the `--max-hits` value that triggered the stop |

`LinkedHashMap` is used (rather than `HashMap`) to preserve insertion order, so results appear in the order pages were visited — which is the BFS/DFS order of the crawl.

### 11.2 `ErrorType` enum

- `NETWORK_ERROR` — DNS failure, connection refused, timeout, SSL error, unexpected HTTP status
- `BLOCKED` — HTTP 403, HTTP 429, or bot-protection page detected
- `PARSE_ERROR` — Jsoup failed to parse the HTML
- `SKIPPED_SIZE` — response body exceeded `--max-bytes`
- `SKIPPED_TYPE` — (reserved for future use)

All error types are pre-populated to 0 in the constructor so callers never need null checks.

### 11.3 `addMatch()` and `addSnippets()`

```java
public void addMatch(String url, int count) {
    results.merge(url, count, Integer::sum);
    totalMatches += count;
}
```

`merge()` creates a new entry or adds to an existing one. A URL may be visited more than once in theory (though the deduplicator prevents this for most cases).

---

## 12. Reporting: `FileMatch.java` & `FileScanResult.java`

**File:** `src/main/java/com/webgrep/reporting/FileMatch.java`  
**File:** `src/main/java/com/webgrep/reporting/FileScanResult.java`

Both are Java records — immutable data carriers with no behaviour beyond `FileScanResult.totalMatches()`.

```java
public record FileMatch(int page, int line, int count, String snippet) {}
```

- `page` — 1-based page number for multi-page documents; 0 for plain text (no page structure)
- `line` — 1-based line number within the page
- `count` — number of keyword occurrences on this line
- `snippet` — the trimmed line content, truncated to 120 characters

```java
public record FileScanResult(String path, List<FileMatch> matches) {
    public int totalMatches() {
        return matches.stream().mapToInt(FileMatch::count).sum();
    }
}
```

One `FileScanResult` per file. `totalMatches()` is a convenience aggregator used in both text and JSON output.

---

## 13. Reporting: `ReportWriter.java`

**File:** `src/main/java/com/webgrep/reporting/ReportWriter.java`

Formats results and prints them to `System.out`. Six public methods — one text and one JSON variant for each of the three modes.

### 13.1 Web crawl text output — `printTextOutput()`

```
--- WebGrep Results ---
Duration: 4.23s
Total matches found: 7
Pages visited: 12
  HTML pages: 11
  Documents:  1

Found in:
  https://example.com/report.pdf (5)
    "…annual revenue exceeded 4.2M in the…"
  https://example.com/ (2)
    "…the domain example.com is used…"
```

Results are sorted by match count (descending) then by URL (ascending). Up to 3 snippets are shown per URL; if there are more matches than snippets, `(+ N more)` is appended.

Blocked URLs are grouped by reason so a flood of 403s doesn't spam hundreds of lines (capped at 10 URLs per reason group).

### 13.2 JSON output — `printJsonOutput()`

All JSON is built manually via `StringBuilder` rather than a JSON library. This avoids adding a dependency for a relatively simple output structure. Special characters in strings are escaped by `escapeJson()`, which handles `\`, `"`, newlines, carriage returns, tabs, and any control character below `0x20`.

The JSON structure is:
```json
{
  "query": { "url", "depth", "keyword", "mode" },
  "stats": { "duration_ms", "total_matches", "pages_visited", "pages_parsed", "docs_parsed", "pages_blocked", "errors": { … } },
  "stopped_early": "…",          ← only if --max-hits triggered early stop
  "results": [ { "url", "count", "snippets": […] } ],
  "blocked": [ { "url", "reason" } ]
}
```

### 13.3 Folder / file variants

`printFolderTextOutput()` and `printFileTextOutput()` follow the same pattern but show file paths and line numbers instead of URLs. The `hasPages` flag controls whether page numbers appear in the output — it is `true` only when at least one `FileMatch` has `page > 0` (i.e. when Tika produced page separators, meaning the source was a multi-page document like a PDF).

### 13.4 `formatDuration(long ms)`

```java
if (ms < 60_000) return String.format("%.2fs", ms / 1000.0);
return String.format("%dm %.2fs", minutes, seconds);
```

Short runs display as `4.23s`; longer runs display as `1m 32.00s`.

---

## 14. Utilities: `UrlUtils.java`

**File:** `src/main/java/com/webgrep/utils/UrlUtils.java`

Two static utility methods used throughout the codebase.

### 14.1 `normalizeUrl(String urlString, String baseUrlString)`

Converts any URL (relative, protocol-relative, or absolute) to a canonical absolute `http://` or `https://` URL.

Steps in order:
1. **Protocol-relative** (`//example.com/…`): prepend `https:` or `http:` based on the base URL's scheme.
2. **Non-http(s) schemes**: any explicit non-http scheme (`ftp:`, `javascript:`, `data:`, etc.) is rejected and returns `""`.
3. **Relative URLs**: resolved against `baseUrlString` using `new URL(base, relative)`.
4. **Normalisation**: lowercases scheme and host; removes default ports (80 for http, 443 for https); collapses `//` in paths to `/`.
5. **Fragment stripping**: fragments (`#section`) are naturally dropped by `URL.getPath()` / `URL.getQuery()`.

Returns `""` on any failure so callers can filter with `!url.isEmpty()`.

### 14.2 `isDocumentLink(String url)`

Returns `true` if the URL path (after stripping fragment and query) ends in a known document extension: `.pdf`, `.doc`, `.docx`, `.txt`, `.xlsx`, `.odt`, `.pptx`, `.csv`.

Used by `Crawler` to split links into `docLinksToEnqueue` (bypass depth limit) and `navLinks` (respect depth limit).

### 14.3 `isIgnoredLink(String url)`

Returns `true` for URLs that should never be fetched:

**Static assets:** `.css`, `.js`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.ico`, `.woff`, `.woff2`, `.ttf`, `.otf`, `.mp3`, `.mp4`, `.wav`, `.avi`, `.mov`, `.wmv`, `.zip`, `.rar`, `.7z`, `.tar.gz`

**Social share redirect URLs:** `googleads`, `doubleclick`, `facebook.com/sharer`, `twitter.com/intent/tweet`, `linkedin.com/share`, `pinterest.com/pin`

**Taxonomy pages** (typically thousands of near-duplicate listing pages): `/tag/`, `/tags/`, `/author/`

Document extensions (PDF, DOCX, etc.) are explicitly **not** ignored — `isDocumentLink()` is called first and returns `false` from `isIgnoredLink()` if it matches.

---

## 15. Tests

**Directory:** `src/test/java/com/webgrep/`

| File | What it tests |
|---|---|
| `AppIntegrationTest.java` | End-to-end integration: all three modes (web excluded — needs network), text and JSON output, all match modes, `--max-bytes`, `--max-hits`, Windows line endings, multiple matches per line |
| `ContentExtractorTest.java` | HTML text extraction, Tika binary extraction, link extraction, edge cases |
| `MainTest.java` | `UrlUtils` (normalizeUrl, isIgnoredLink), `MatchEngine` (countMatches, superSimplify, findSnippets), `CrawlResult` accumulation, `Main.findLineMatches` (page detection, truncation, multi-match lines), `CliOptions` parsing and validation edge cases |
| `ReportWriterTest.java` | JSON escaping, duration formatting, blocked URL grouping, stopped-early output |
| `UrlDeduplicatorTest.java` | Dedup rules: superset detection, `--all-urls` mode, scheme normalisation, no-params base path handling |

All tests use JUnit 4. There are no mocks — `AppIntegrationTest` writes real temp files and calls `Main.main()` directly with stdout redirected to a `ByteArrayOutputStream` for assertions.

---

## 16. Build & deployment

**`pom.xml`:** Maven build file.

- Java 17 source and target.
- `maven-shade-plugin` creates a fat JAR (`WebGrep-{version}.jar`) containing all dependencies.
- The `ServicesResourceTransformer` merges `META-INF/services/` files so Tika's format-detection plugins (registered via SPI) are all preserved in the shaded JAR.
- Signature files (`.SF`, `.DSA`, `.RSA`) are excluded — they become invalid after shading and would cause JAR verification failures.

**Dockerfile:** Multi-stage build.
- Stage 1: Maven on `eclipse-temurin:17` compiles and packages the JAR.
- Stage 2: `eclipse-temurin:17-jre-alpine` (lightweight) copies only the shaded JAR from stage 1.
- Result: a ~90 MB Docker image vs the ~350 MB image you'd get from the full JDK stage.

**Deployment script (implied by memory):**
```bash
cp target/WebGrep-1.1.4.jar ~/.local/share/webgrep/WebGrep.jar
```
The shell wrapper at `~/.local/bin/webgrep` points to this JAR location.
