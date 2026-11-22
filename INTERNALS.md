# WebGrep - Code Internals

A detailed walkthrough of every source file: what it does, why it works the way it does, and how the pieces connect.

---

## Table of Contents

1. [High-level data flow](#1-high-level-data-flow)
2. [Package map](#2-package-map)
3. [Entry point - `Main.java`](#3-entry-point--mainjava)
4. [Configuration - `CliOptions.java`](#4-configuration--clioptionsjava)
5. [Core: `Crawler.java`](#5-core-crawlerjava)
6. [Core: `PlaywrightRenderer.java`](#6-core-playwrightrendererjava)
7. [Core: `BrowserFinder.java`](#7-core-browserfinderjava)
8. [Core: `ContentExtractor.java`](#8-core-contentextractorjava)
9. [Core: `MatchEngine.java`](#9-core-matchenginejava)
10. [Core: `UrlDeduplicator.java`](#10-core-urldeduplicatorjava)
11. [Reporting: `CrawlResult.java`](#11-reporting-crawlresultjava)
12. [Reporting: `FileMatch.java` & `FileScanResult.java`](#12-reporting-filematchjava--filescanresultjava)
13. [Reporting: `ReportWriter.java`](#13-reporting-reportwriterjava)
14. [Utilities: `UrlUtils.java` & `ConsoleText.java`](#14-utilities-urlutilsjava--consoletextjava)
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
                       └─ fetch() → fetchWithRetry()                ← Jsoup HTTP, one redirect hop at a time
                       └─ CookieJar.header() / store()              ← cookies per request
                       └─ ContentExtractor.extractTextFromHtml()    ← Jsoup DOM
                          or ContentExtractor.extractTextFromBinary() ← Tika
                       └─ PlaywrightRenderer.render()  (SPA only)  ← Playwright
                       └─ MatchEngine.countMatches() / findSnippets()
                       └─ UrlDeduplicator.isDuplicate() / markQueued()
                       └─ CrawlResult (accumulates everything)
                       └─ ReportWriter.printText/JsonOutput()
```

Three modes share `ContentExtractor` and `MatchEngine`. Only the web crawl mode uses `Crawler`, `CookieJar`, `PlaywrightRenderer`, `BrowserFinder`, and `UrlDeduplicator`.

---

## 2. Package map

```
com.webgrep
├── Main.java                    ← entry point, mode routing
├── config/
│   └── CliOptions.java          ← CLI parsing & validation
├── core/
│   ├── BrowserFinder.java       ← system Chromium/Chrome discovery
│   ├── ContentExtractor.java    ← HTML → text (Jsoup), binary → text (Tika), PDF page marks
│   ├── CookieJar.java           ← cookie store following browser rules (RFC 6265)
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
    ├── ConsoleText.java         ← escapes control characters before terminal output
    └── UrlUtils.java            ← URL normalisation, link filtering
```

---

## 3. Entry point - `Main.java`

**File:** `src/main/java/com/webgrep/Main.java`

`Main` is the only class with a `main` method. It does four things:

### 3.1 Silence library logging

```java
private static void setupLogging() { … }
```

Tika, SLF4J, and Playwright all emit noisy logs to stderr by default. `setupLogging()` runs first and silences them so the user only sees WebGrep's own output: SLF4J Simple is set to report errors only, and the JUL root logger is switched off. Apache Commons Logging, used by PDFBox and other Tika parsers, is bridged to SLF4J by `jcl-over-slf4j`, so the SLF4J setting covers it too.

### 3.2 Parse args and route to the right mode

```java
CliOptions options = CliOptions.parse(args);
```

After parsing, `Main` checks three mutually exclusive branches:

| Condition | Mode |
|---|---|
| `options.getFolder() != null` | folder scan - `scanFolder()` |
| `options.getFile() != null` | single file - `scanLocalFile()` |
| otherwise | web crawl - `new Crawler(…).crawl()` |

### 3.3 `scanFolder()`

Lists the files with `listFiles(root)`, then for each file:
- Skips it if it exceeds `--max-bytes` (counted as `skipped`)
- Reads the raw bytes and calls `ContentExtractor.extractTextFromBinary()` (Tika handles all formats)
- Calls `findLineMatches()` to search the extracted text
- Accumulates into a `List<FileScanResult>`
- Stops early if `--max-hits` is reached

A progress line is printed to stderr during the scan and erased when done. File names in it pass through `ConsoleText.safe()`, because a file name can contain control characters.

`listFiles(root)` walks the tree with `Files.walkFileTree()`, which keeps going past unreadable subdirectories, and returns the sorted list of **regular files only**:
- Symbolic links are skipped, so a file is never searched twice and a link cycle cannot trap the walk.
- Named pipes, sockets and device files are skipped. Reading a pipe blocks until something writes to it, so including one would hang the scan; a device such as `/dev/zero` never ends.
- The root itself may be a symbolic link to a folder. The walk does not follow links, so it would otherwise see the root as a link and find nothing; it starts from the link's target instead, and the paths it returns are still expressed under the root the user typed.

`scanLocalFile()` applies the same rule to a single file: a folder or special file is rejected with a configuration error instead of being read.

### 3.4 `findLineMatches()`

This is the core local-search loop used by both `scanFolder()` and `scanLocalFile()`:

```java
static List<FileMatch> findLineMatches(String text, String keyword, String mode, MatchEngine matchEngine)
```

1. Normalises line endings (`\r\n` → `\n`, `\r` → `\n`).
2. Splits on `\f` (form feed) to separate pages. `ContentExtractor` marks the pages of a PDF with form feeds (see 8.3). If no `\f` is found, the document has no page structure and `page = 0` is recorded.
3. Searches **each page as a whole** with `MatchEngine.findMatches()`, which returns the position of every match. Searching line by line would miss a phrase broken across lines - and PDF text breaks a line wherever the printed line ends. Each match is reported on the line where it starts, and matches starting on the same line are grouped into one `FileMatch`.
4. Numbers the lines. In plain text every line counts, as in grep. In paged documents only non-blank lines count: the blank lines in extracted PDF text are layout artifacts, and counting them would point past the line the user sees on the page.
5. Builds the snippet: the matching line, joined with the following line when the match continues there, with tabs and line breaks shown as spaces. A snippet longer than 120 characters is cut around the match (`fitAround()`), so the match is always visible.

Fuzzy mode is the one special case. It falls back to typo matching only where no close match was found, and local scans have always decided that line by line: close matches are found across the whole page, then each line that no close match touches gets its own typo search (`MatchEngine.findTypoMatches()`).

### 3.5 `installBrowser()`

Handles the `--install-browser` flag. It exits with status 2 when it ends without a usable browser, so a script that prepares a machine for SPA crawls stops there. First asks `PlaywrightRenderer.findUsableBrowser()` whether a browser WebGrep can use is already there, in the renderer's own order (6.3). That check launches each candidate and closes it again, so "already available" means "starts" - a cached build left behind by a different Playwright version, or an installed branded Firefox, does not count.

If nothing starts, the browser named by `--browser` is installed, or the user is asked to choose (Firefox by default). `PlaywrightRenderer.installBrowser()` runs the official Playwright installer in a subprocess, into WebGrep's own browser folder, and the result is verified by launching it.

### 3.6 Error handling

Two catch levels:
- `IllegalArgumentException` → configuration error (bad flags, missing required args) → exits with code 1
- `Exception` → fatal runtime error → prints the exception chain and exits with code 2

---

## 4. Configuration - `CliOptions.java`

**File:** `src/main/java/com/webgrep/config/CliOptions.java`

`CliOptions` is a pure data class that parses raw `String[] args` and validates the result. No logic beyond parsing and validation - it does not execute anything.

### 4.1 How parsing works

`parse()` iterates through `args` one token at a time:
- Long flags (`--depth 2`) are recognised by the `KNOWN_FLAGS` set. Unknown flags throw `IllegalArgumentException` immediately so typos are caught at startup.
- Short flags (`-d 2`) are resolved through `mapShortFlag(char)`, a switch expression that maps single characters to canonical flag names.
- Boolean flags (e.g. `--allow-external`, `--dfs`) call `params.put(key, "true")` and consume no next token.
- Value flags (everything else) consume the next token as the value, except when the next token looks like another flag (`startsWith("-")` but is not a negative number).

Negative number detection: the condition `args[i+1].matches("-\\d.*")` allows values like `-r -500` (negative delay) to be parsed correctly without being mistaken for a flag.

Any other value that starts with `-` - searching for `--force`, say - is written in the attached form `--keyword=--force`. A detached value starting with `-` is still rejected, so a forgotten value (`-u -k x`) is reported instead of silently swallowing the next flag; the error message suggests the attached form. A boolean flag given a value (`--dfs=true`) is rejected.

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
- `--url` scheme must be `http` or `https`, in any letter case (`HTTPS://example.com` is valid).
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

`Crawler` is the most complex class. It implements a configurable BFS/DFS web crawl with SPA detection, redirect handling, cookie management, and retry logic.

### 5.1 State

```java
private final CookieJar cookieJar = new CookieJar();
private Boolean spaRenderingEnabled;   // null=not asked, true/false=decided
private String startDomain;
private boolean allowSubdomains;
private final UrlDeduplicator dedup;
private final SSLSocketFactory insecureSslFactory;
```

Cookies live in a `CookieJar` (5.8), which keeps every cookie's `Domain`, `Path`, `Secure` and expiry attributes and sends each one only where they allow - the rules a browser follows. The crawler asks it for a `Cookie` header on every request, including every redirect hop, and gives it every response's `Set-Cookie` headers.

`spaRenderingEnabled` is a three-state `Boolean` (boxed):
- `null` - the user hasn't been asked yet
- `true` - user said yes (or non-interactive mode)
- `false` - user said no

### 5.2 Domain scoping

```java
this.startDomain = seedHasWww ? startHost.substring(4) : startHost;
this.allowSubdomains = seedHasWww;
```

If the seed URL is `www.example.com`, WebGrep strips `www.` to get the root domain `example.com` and sets `allowSubdomains = true`. This means `docs.example.com`, `en.example.com`, etc. are all followed. If the seed is `docs.example.com` (no `www.`), only the exact domain and its `www.` alias are allowed.

`isSameDomain(linkHost)` implements this check.

Redirects and the scope:
- **The seed** may redirect anywhere. A site that moved domains is still the site the user asked for, so the scope moves to wherever the seed ends up (`switchScopeTo()`). A seed page whose zero-delay `<meta http-equiv="refresh">` points to another site is treated the same way.
- **Any other redirect** that would leave the scope is not followed: nothing is requested from the other site, and the redirect is recorded in `CrawlResult.offsiteRedirects` and listed in the output. Refresh redirects that point off-site are recorded the same way. `--allow-external` lifts both restrictions.

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

1. **Fetch** via `fetch()` (5.5), which follows redirects one hop at a time and stops at one that would leave the scope.
2. **Track effective URL** - the final URL after redirects is the canonical URL for this page. It is marked as queued so no other link to it triggers a duplicate visit.
4. **Check Content-Type:**
   - `text/html` or `application/xhtml+xml` → HTML path
   - Anything else → binary path (Tika)

**HTML path:**
- Jsoup parses the response into a `Document`.
- Cloudflare/bot-protection check: if the page title contains "Just a moment..." or body text contains "Enable JavaScript and cookies to continue", the URL is added to `blockedUrls`.
- `ContentExtractor.extractTextFromHtml(doc)` extracts title + body text + meta tags.
- `ContentExtractor.extractLinks(doc, …)` extracts all `<a href>` links and splits them into:
  - `docLinksToEnqueue` - links whose path ends in a known document extension (PDF, DOCX, etc.)
  - `navLinks` - all other links (HTML pages, unknown paths)
- **Meta refresh:** `metaRefreshTarget(doc, url)` finds a zero-delay `<meta http-equiv="refresh">` target, if any.
- **SPA detection:** calls `PlaywrightRenderer.isSpa(doc)`. If true, the user is prompted (once) and `PlaywrightRenderer.render()` is called with the jar's cookies. The rendered result replaces the Jsoup-extracted text and links, and the cookies the browser holds afterwards go back into the jar.

**Binary path:**
- `ContentExtractor.extractTextFromBinary()` passes the bytes to Tika.
- `crawlResult.docsCount++` increments the document counter.

**Matching:**
- `MatchEngine.countMatches()` counts occurrences in the extracted text.
- `MatchEngine.findSnippets()` extracts up to 3 context snippets around each match.
- Results are recorded via `crawlResult.addMatch()` and `crawlResult.addSnippets()`.

**Link enqueuing - the key distinction:**
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

The reason document links bypass the depth check: documents are leaf nodes - they don't link to other pages, so enqueuing them can never cause the crawl to expand exponentially beyond the intended boundary. A PDF linked from a depth-1 page should be fetched even if `--depth 1` would otherwise stop there.

Navigation links are capped at the depth limit to prevent unbounded crawls.

A **meta-refresh target** is enqueued at the *current* depth and bypasses the depth limit: it is the page a browser would show in place of this one, so its links are this page's links. Enqueuing it one level down would make a site whose front page is a refresh stub stop one level early - with the default `--depth 1`, finding nothing at all.

**The page budget:** only pages actually visited count towards `--max-pages`; a fetch that fails does not. Links are therefore not rationed at the moment they are discovered - rationing them by the budget would drop good links in favour of broken ones queued earlier. The queue is bounded only by `MAX_QUEUED_URLS` (100,000), a memory guard for huge sites.

### 5.5 `fetch()` and `fetchWithRetry()`

```java
private Connection.Response fetch(String url, boolean allowOffsite) throws Exception {
    String current = url;
    for (int hops = 0; ; hops++) {
        Connection.Response response = fetchWithRetry(current);
        String target = redirectTarget(response, current);
        if (target == null) return response;
        …
        if (!allowOffsite && !isSameDomain(extractHost(target))) throw new OffsiteRedirectException(target);
        current = target;
    }
}
```

Redirects are followed here, not by Jsoup, for two reasons:
- **Cookies.** Jsoup sends the cookies a request starts with to every hop of its redirect chain, whatever the host. Following hops by hand makes each hop a new request whose `Cookie` header the `CookieJar` builds for that hop's own URL, so a session cookie for one host is never carried along to another.
- **Scope.** Each hop's target is checked before anything is sent to it. A redirect off the site throws `OffsiteRedirectException`, which the crawl loop records. `allowOffsite` is true for the seed (5.2) and under `--allow-external`.

At most 20 hops are followed (Jsoup's own limit); a longer chain is an error. Only 301, 302, 303, 307 and 308 with a `Location` header count as redirects.

`fetchWithRetry()` makes one request:

```java
if (status == HTTP_TOO_MANY_REQUESTS && attempt < MAX_RETRIES) {
    long waitMs = parseRetryAfter(response.header("Retry-After"), 1000L << attempt);
    Thread.sleep(waitMs);
} else if (status >= 400) {
    throw new HttpStatusException(…);
} else {
    return response;   // may be a redirect - fetch() decides
}
```

On HTTP 429, waits using exponential backoff (2s, 4s) or the value from the `Retry-After` header, capped at 60 seconds. Non-429 4xx and 5xx errors are thrown immediately as `HttpStatusException`. Every response's `Set-Cookie` headers go into the jar first - error and redirect responses included, since some challenge flows set a cookie on a 429 that must be sent back on the retry.

The `User-Agent` header mimics Chrome on Linux, which gets through more WAFs than an obvious bot string.

### 5.6 SSL / insecure mode

When `--insecure` is passed, `buildInsecureSslFactory()` creates a `TrustManager` that accepts all certificates and all hostnames. This involves a global `HttpsURLConnection.setDefaultHostnameVerifier()` call (Jsoup has no per-connection API for this). The original verifier is saved and restored after the crawl in the `finally` block.

### 5.7 Progress display

```java
private void printProgress(String currentUrl, int visited, int matches) {
    System.err.printf("\r%-110s", line);
}
```

Uses `\r` (carriage return) to overwrite the same line in the terminal. Printed to `stderr` so it doesn't interfere with JSON output on `stdout`. The URL passes through `ConsoleText.safe()` first: it comes from page markup, and a control character in it would otherwise reach the terminal as an instruction.

### 5.8 `CookieJar.java`

**File:** `src/main/java/com/webgrep/core/CookieJar.java`

The crawler's cookie store, applying the rules browsers follow (RFC 6265). Each `Cookie` record keeps `name`, `value`, `domain`, `hostOnly`, `path`, `secure`, `httpOnly` and `expiresAt`.

- **`store(url, setCookieHeaders)`** parses each header (`parse()`, RFC 6265 section 5.2). Without a `Domain` attribute the cookie is *host-only*: it goes back to exactly the host that set it. With `Domain=example.com` it goes to that domain and its subdomains - but a server may only name its own domain or a parent of it, never a sibling, an unrelated site, or a bare top-level domain, and an IP address can only set cookies for itself. A cookie with a past expiry deletes the stored one. `Max-Age` wins over `Expires`; `Expires` dates are parsed with the RFC's lenient algorithm (`parseDate()`), and an unparseable date leaves a session cookie rather than deleting it.
- **`header(url)`** returns the `Cookie` header for a request: cookies whose domain matches the host, whose path is a prefix of the request path on a `/` boundary, and - for `Secure` cookies - only over HTTPS. Longest path first.
- **`all()` / `add()`** hand cookies to the SPA renderer and take back the ones the browser holds, attributes intact.

Known simplification: without the public suffix list, a domain such as `co.uk` is accepted if a host below it names it. Browsers reject that; for a keyword crawler the only effect is that such a cookie is shared more widely than intended.

---

## 6. Core: `PlaywrightRenderer.java`

**File:** `src/main/java/com/webgrep/core/PlaywrightRenderer.java`

`PlaywrightRenderer` manages a headless browser session for rendering SPA pages. It implements `AutoCloseable` so the browser is properly shut down when the crawl ends.

### 6.1 The `RenderedPage` record

```java
public record RenderedPage(String text, List<String> links, List<String> docLinks, List<CookieJar.Cookie> cookies) {}
```

- `text` - the rendered page's title, body text, and description and keywords meta tags, extracted the same way as for a plain HTML page (6.7)
- `links` - all `<a href>` URLs found in the rendered page (fully resolved via `a.href`)
- `docLinks` - document download URLs found in intercepted JSON responses + `<a download>` links
- `cookies` - cookies of the page's site that the browser holds after the render, with their attributes, to be returned to the crawler's `CookieJar`

### 6.2 SPA detection - `isSpa(doc)`

```java
public static boolean isSpa(Document doc) { … }
```

Called by `Crawler` on the Jsoup-parsed HTML before any browser is started. Returns `true` if any of these signals are present:
- `<html ng-version="…">` - Angular
- `<html data-beasties-container>` - Angular SSR (static shell)
- `<html data-n-head>` - Nuxt.js
- `[data-reactroot]` - React
- `<script id="__NEXT_DATA__">` - Next.js
- `<app-root>` with < 100 characters of text - Angular (empty shell)
- `<div id="root">` or `<div id="app">` with < 100 characters - React/Vue (empty shell)
- Body text < 100 chars + any JS bundles - generic SPA shell
- Body text < 300 chars + 2 or more JS bundles - stronger generic SPA signal

### 6.3 Browser resolution - `ensureReady()`

Called every time `render()` is invoked; returns at once while the browser is connected. Otherwise `launchInstalled()` tries every browser that needs no download, in order, and keeps the first that starts:

| Tier | What happens |
|---|---|
| 1. System Chromium/Chrome | Found via `BrowserFinder.findChromium()` → launched with `executablePath` |
| 2. Playwright's Firefox | `firefox-*` in WebGrep's own browser folder, then in the shared Playwright cache |
| 3. Playwright's Chromium | `chromium*` in the same two places |
| 4. Download | User is prompted (or `--browser` preference is used); the browser is downloaded into WebGrep's own folder via subprocess |

`--browser firefox` skips the Chromium tiers. `--browser chromium` skips the Firefox tier. A cached build that does not start - one installed by a different Playwright version, say - is skipped.

**No system Firefox tier.** Playwright drives Firefox through a protocol (Juggler) that exists only in its own patched build, so a branded Firefox - stable, ESR, Developer Edition or Nightly - fails to start. Trying one cost a failed launch and, worse, let `--install-browser` report "no installation needed" for a browser that could never be used.

**WebGrep's own browser folder** (`browsersPath()`): `$XDG_CACHE_HOME/webgrep/browsers` (`~/.cache/webgrep/browsers`) on Linux, `~/Library/Caches/webgrep/browsers` on macOS, `%LOCALAPPDATA%\webgrep\browsers` on Windows. Playwright normally shares one cache (`ms-playwright`) between every program that uses it, and whenever any of them installs a browser it deletes the cached browsers no living installation claims. WebGrep's claim points into the temporary directory Playwright for Java unpacks its driver to, which is gone once WebGrep exits - so another project's `playwright install` would delete WebGrep's browser. Downloads therefore go into WebGrep's own folder, which nothing else touches; browsers already in the shared cache are still used if they start. If `PLAYWRIGHT_BROWSERS_PATH` is set, that folder alone is used, for lookups and installs.

In non-interactive mode (`System.console() == null`), tier 4 throws an `IOException` instead of prompting, which marks the renderer `unavailable` and prints a warning.

**Why tier 4 uses a subprocess:** `com.microsoft.playwright.CLI.main()` calls `System.exit(0)` when the download completes. Calling it in-process during an active crawl would kill the JVM and discard all results collected so far. Instead, `installBrowser()` spawns a child process via `ProcessBuilder` to run the installer, with `PLAYWRIGHT_BROWSERS_PATH` pointing at WebGrep's folder. The parent JVM waits for the child to finish, then launches the browser normally.

`initPlaywright(dir)` sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` in the environment to prevent Playwright from downloading any browser during `Playwright.create()` - only an explicit subprocess `CLI install …` should download anything - and `PLAYWRIGHT_BROWSERS_PATH` to the folder being tried.

### 6.4 Persistent browser context

```java
private BrowserContext persistentContext;
private Page persistentPage;
private String persistentBaseUrl;
```

The browser context and page are kept alive across multiple calls to `render()` as long as they are on the same domain (same `baseUrl`). This is a major performance optimisation for SPAs:
- On the **first navigation** to a domain, the JavaScript bundle must be downloaded, parsed, and executed. This can take 10 - 15 seconds.
- On **subsequent navigations** to the same domain (e.g. different routes of the same Angular app), the router just swaps components. The bundle is already loaded, so navigation typically takes 150 - 400 ms.

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
Instead of waiting for `NETWORKIDLE` (which can take several seconds), WebGrep polls every 50 ms for the content area to contain at least 100 characters of text. This resolves much faster (typically in 150 - 400 ms).

### 6.7 DOM snapshot - why `a.href` instead of `a.getAttribute('href')`

```javascript
const h = a.href;  // DOM property - fully resolved by browser
```

The key insight: the browser DOM property `a.href` always returns the **fully resolved absolute URL**. The browser applies the page's `<base href>` tag automatically.

The HTML attribute `a.getAttribute('href')` returns the **raw string from the HTML source**, which may be a bare relative path like `api/v1/files/123/download`.

Problem with `getAttribute`: Angular and other SPAs set `<base href="/app/">` in the HTML head. Relative links like `api/v1/…` are then supposed to be resolved against that base - so the correct URL is `https://example.com/app/api/v1/…`. But if you resolve them manually using `new URL(rawHref, pageURL)`, where `pageURL` is something like `https://example.com/app/uredni-deska/detail`, you get `https://example.com/app/uredni-deska/api/v1/…` - wrong path, 404.

Solution: use `a.href`, let the browser handle the `<base href>` resolution, and receive the already-correct absolute URL.

The same snapshot returns the page's text source: the **HTML** of a clone of the page body, with `script`, `style`, `noscript` and `template` elements removed. Java turns it into text with Jsoup (`renderedText()`), exactly as for a plain HTML page. The whole body matters: an app usually mounts into one element (`#root`, `app-root`) while the page around it holds static text such as a footer, and the rendered text replaces the plain fetch's text, so taking only the mount point would lose text that a plain fetch had found. Taking `textContent` in the browser instead would glue words together: it puts nothing between elements, and React, Vue and Angular output has no whitespace between them, so `<h1>Hello</h1><p>World</p>` would read as `HelloWorld` and the phrase "hello world" would never match.

The `raw` variable from `a.getAttribute('href')` is still checked first - but only to filter out `javascript:`, `mailto:`, and pure fragment links (`#` or `#something`), which cannot be detected by checking `a.href` alone (the browser resolves `#` to the full current page URL).

### 6.8 JSON API interception - `captureDocLinks()`

```java
persistentPage.onResponse(
    response -> captureDocLinks(response, this.interceptorUrlBase, this.interceptedLinks));
```

Playwright fires an event for every HTTP response the browser receives. `captureDocLinks()` is the handler:

1. Only processes HTTP 200 responses with `Content-Type: application/json`.
2. Skips responses larger than 5 MB to avoid blocking the browser's network thread.
3. Applies two regex patterns to the response body:
   - `JSON_DOC_URL` - matches URLs ending in known document extensions (`.pdf`, `.docx`, `.xlsx`, etc.) inside JSON strings.
   - `JSON_DOWNLOAD_URL` - matches URLs whose final path segment is literally `/download` (common REST API pattern for file download endpoints like `/api/v1/files/123/download`).
4. Resolves relative URLs against the domain base and adds them to `interceptedLinks`.

`interceptedLinks` is a `Collections.synchronizedList` because the response handler runs on Playwright's internal network thread, not the main thread.

The `interceptorUrlBase` is the scheme + host of the current page (e.g. `https://example.com`). It is set before each `navigate()` call and used to prefix relative paths found in JSON.

### 6.9 Cookie round-trip

```java
private static void passCookies(BrowserContext context, List<CookieJar.Cookie> cookies) { … }
```

When a new browser context is created, the crawler's cookies are added to it with their attributes intact (`toBrowserCookie()`): a domain cookie keeps its leading dot, a host-only cookie does not, and `Path`, `Secure`, `HttpOnly` and expiry carry over. The browser then sends each one only where it belongs. This ensures pages that require session cookies render correctly.

After navigation, the cookies the browser holds for the page's own site are returned in `RenderedPage.cookies()` (`fromBrowserCookie()`), and the crawler adds them to its jar for subsequent Jsoup requests. Third-party cookies set by analytics scripts stay in the browser.

---

## 7. Core: `BrowserFinder.java`

**File:** `src/main/java/com/webgrep/core/BrowserFinder.java`

Locates a system-installed Chromium or Google Chrome binary without any external dependencies. Used by `PlaywrightRenderer` for tier 1 (6.3).

Firefox is deliberately not looked for: Playwright can only drive its own patched Firefox build, so a system Firefox of any edition fails to start (6.3).

### 7.1 Strategy

`findChromium()`:
1. Gets the hard-coded install paths for the detected OS (macOS / Windows / Linux) from `knownChromiumPaths()`.
2. Checks each path with `Files.isExecutable(p)`.
3. If no hard-coded path works, calls `findViaShell()` as a fallback.

### 7.2 `findViaShell(String... names)`

Runs `which <name>` (Linux/macOS) or `where <name>` (Windows) for each browser name in order. Parses the first line of output, checks that the result is executable, and returns it.

This handles non-standard install locations (e.g. browsers installed via Flatpak, AUR, nix, or a custom prefix).

### 7.3 Search order

- Linux: `/usr/bin/chromium-browser`, `/usr/bin/chromium`, `/usr/bin/google-chrome`, `/usr/bin/google-chrome-stable`, `/snap/bin/chromium`; then `which` for `chromium-browser`, `chromium`, `google-chrome`, `google-chrome-stable`
- macOS: Chromium.app, Google Chrome.app
- Windows: `%LOCALAPPDATA%\Chromium`, `%LOCALAPPDATA%\Google\Chrome`, `%ProgramFiles%\Chromium`

---

## 8. Core: `ContentExtractor.java`

**File:** `src/main/java/com/webgrep/core/ContentExtractor.java`

Responsible for converting raw bytes (HTML or binary documents) into plain searchable text strings.

### 8.1 HTML extraction - `extractTextFromHtml(Document doc)`

Takes a Jsoup `Document` already parsed from HTML and returns a concatenation of:
- `doc.title()` - the `<title>` tag
- `doc.body().text()` - all visible text from the body, whitespace-collapsed by Jsoup
- `meta[name=description]` content
- `meta[name=keywords]` content

This single string is what `MatchEngine` searches.

### 8.2 Binary extraction - `extractTextFromBinary(byte[] body, String url, String contentType)`

Used for PDFs, DOCX files, and any other non-HTML response. The flow:

1. Creates a Tika `Metadata` object. Setting `RESOURCE_NAME_KEY` to the URL (or filename) lets Tika use the file extension as a hint for format detection.
2. Calls `tikaParseWithTimeout(body, metadata)`.
3. If that returns empty or null, retries without metadata (format detection from magic bytes only).
4. If Tika fails entirely, falls back to raw `UTF-8` interpretation of the bytes.

### 8.3 `tikaParseWithTimeout()` and page marks

```java
private String tikaParseWithTimeout(byte[] body, Metadata metadata) {
    Future<String> future = TIKA_EXECUTOR.submit(() -> parseWithPageBreaks(body, metadata));
    try {
        return future.get(TIKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        future.cancel(true);
        return null;
    }
}
```

Tika can hang on corrupt or malformed files. Wrapping it in a `Future` with a 30-second timeout prevents one bad file from blocking the entire crawl or folder scan.

`TIKA_EXECUTOR` is a daemon thread pool - daemon threads don't prevent JVM shutdown if the main thread exits.

`parseWithPageBreaks()` does what `Tika.parseToString()` does - auto-detect the format, parse embedded documents, stop quietly at the string-length limit - but routes the text through `PageBreakHandler`. Tika reports each page of a PDF as a `<div class="page">` element, which its plain-text output reduces to blank lines; the page boundaries would be lost. `PageBreakHandler` writes a form feed (`\f`) at the start of every page after the first, and one more at the end of the document:
- Markers go at the start of the next page, not the end of the previous one, so text Tika emits after the last page (bookmarks, form-field values) stays with the last page instead of becoming a page that does not exist.
- The final marker closes the last page, so even a one-page PDF is recognisably paged.
- Only top-level pages are marked. A PDF embedded in another document (an attachment, an entry in a ZIP) would otherwise continue its container's page count.

### 8.4 `tika.setMaxStringLength()`

```java
int tikaLimit = (int) Math.min(Math.max(maxBytes, 50L * 1024 * 1024), Integer.MAX_VALUE);
this.tika.setMaxStringLength(tikaLimit);
```

Tika's string output is capped independently of `--max-bytes`. The floor is 50 MB - even if the user sets a small `--max-bytes` to throttle download sizes, local documents in `--file` / `--folder` mode should not be silently truncated.

### 8.5 Link extraction - `extractLinks(Document doc, byte[] rawBody, String baseUrl)`

Called by `Crawler` for HTML pages to collect all `<a href>` links.

1. Uses Jsoup's `doc.select("a[href]")` and `element.absUrl("href")` to get absolute URLs (Jsoup resolves them against the document's own base URL).
2. Normalises each URL via `UrlUtils.normalizeUrl()` and filters via `UrlUtils.isIgnoredLink()`.
3. Uses `LinkedHashSet` to deduplicate while preserving insertion order.
4. Caps at 5000 links per page.

Fallback: if Jsoup found no `<a>` elements (the page might be raw HTML or a malformed document), a regex `href="…"` scan is applied to the raw bytes.

---

## 9. Core: `MatchEngine.java`

**File:** `src/main/java/com/webgrep/core/MatchEngine.java`

Implements three keyword matching strategies behind two entry points that share one core:
- `countMatches(text, keyword, mode)` - how many; as cheap as possible, since it runs on every page.
- `findMatches(text, keyword, mode)` - where; returns a `Match(start, end)` for each occurrence. Snippets and the per-line reporting of local scans are built from it.

The two agree: `countMatches` equals the size of `findMatches` for the same arguments, except in default mode for exotic case foldings (such as the long s, `ſ`) that only one of the two passes understands.

### 9.1 Pattern cache and whitespace

```java
private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();
```

Compiling a regex is expensive. The cache ensures each keyword/mode pair is compiled only once, no matter how many pages are searched.

In every mode, **whitespace in the keyword matches any run of whitespace in the text**. `getPattern()` quotes each word of the keyword and joins the words with a whitespace-run class (ASCII whitespace, Unicode space separators such as U+00A0, and the line and paragraph separators). A phrase therefore still matches when the text breaks it across lines - as PDF text does at the end of every printed line - or pads it with tabs or extra spaces. A single-word keyword compiles to exactly `Pattern.quote(keyword)`.

### 9.2 Default mode - case-insensitive with diacritic support

**Literal pass:** the case-insensitive, Unicode-aware pattern. `UNICODE_CASE` makes the case-insensitivity work for non-ASCII letters (e.g. `Ü`, `Č`).

**Simplified pass:** searches a simplified copy of the text for the simplified keyword. Simplification decomposes each character (NFD), drops combining marks, lowercases, and keeps ASCII letters and digits; every run of other characters becomes one space. So `café` → `cafe`, `Tomáš` → `tomas`, `real-time` → `real time`. Searching `Tomas` finds `Tomáš` and vice versa, and `real time` finds `real-time`. Keeping the spaces is what stops `sofa` from matching `so far away`.

`countMatches` returns the higher of the two counts: `"cafe Café"` with keyword `cafe` yields 1 from the literal pass but 2 from the simplified pass. `findMatches` returns their union, an occurrence found by both being reported once.

Guards:
- The simplified pass is skipped when the keyword contains ASCII punctuation (`more*`, `node.js`, `.NET`): turning it into a space would change the intended search term.
- It is skipped when the keyword simplifies to fewer than two characters - `C++` would become `c` and match nearly every word.
- It is skipped when it provably cannot find anything new (`simplifiedPassIsRedundant()`): pure-ASCII text and a single-word ASCII keyword. This is the common case, and skipping it keeps default mode about as fast as exact mode.

Simplification is a single character pass. ASCII is handled inline; other characters go through a per-character cache (`FOLD`) of what each one contributes, so `Normalizer` runs once per distinct character rather than over the whole text. The same routine serves counting and locating (with a position map from simplified back to source characters), so the two can never disagree about the simplified text.

### 9.3 Exact mode

The case-sensitive pattern. `hello` matches `hello` but not `Hello` or `HELLO`; whitespace still matches whitespace runs (9.1).

### 9.4 Fuzzy mode

**Close matches (`findCloseMatches()`):** the keyword is matched with case, diacritics and *punctuation inside words* ignored. The text is simplified so that a run of characters containing whitespace becomes a space but punctuation on its own is dropped; the keyword's parts are joined so that each boundary may match nothing or one space. `node.js`, `nodejs` and `Node-JS` all find each other, and `real time` finds `realtime`. Whitespace in the text is still a word boundary: `sofa` does not match `so far away`.

**Typo matches (`findTypoMatches()`):** only if no close match was found. The text is split into words at every non-alphanumeric character and each word is compared with the simplified keyword by Levenshtein distance (two-row dynamic programming).

Threshold:
- Keyword length ≤ 4: edit distance ≤ 1 (one substitution/insertion/deletion)
- Keyword length > 4: edit distance ≤ 2

Words whose length difference alone exceeds the threshold are skipped without computing the distance.

### 9.5 `findSnippets()`

Returns up to `maxSnippets` distinct context strings - short excerpts with the keyword in the middle.

1. Flattens whitespace (space, tab, CR, LF, form feed, vertical tab, U+00A0) to single spaces, so a snippet fits on one output line and a snippet spanning a page break does not glue two words together.
2. In default and exact mode, collects the literal matches first. A page with many of them fills every slot from this pass alone, so the simplified pass - and its position map, an int per character of page text - is not built for nothing.
3. Otherwise takes the complete list from the same passes as `findMatches`. Every mode therefore produces snippets for what it counts, fuzzy typo matches included. Positions map back to the original text, so the snippet shows `Tomáš` even when the keyword was `Tomas`.

`buildSnippet(flat, start, end)` takes 60 characters before and after the match, then moves each end outward to the nearest space - but by no more than 40 characters. Text without spaces (Chinese, Japanese, minified code) has no word edge to find; without the limit the snippet would swallow the whole page and its 500-character cap would cut the match out of view. Control characters (C0, DEL, C1) are stripped and the snippet is capped at 500 characters.

---

## 10. Core: `UrlDeduplicator.java`

**File:** `src/main/java/com/webgrep/core/UrlDeduplicator.java`

Prevents the crawler from visiting the same page twice. Handles the tricky case of URLs with query parameters.

### 10.1 The problem

A naive `Set<String>` deduplicator would re-visit `?id=1&utm_source=mail` even if `?id=1` was already visited - they are different strings. But treating every added parameter as a harmless variant goes wrong the other way: `?q=x&page=2` is not a variant of `?q=x`, it is the next page of results.

### 10.2 Smart dedup rule (when `allUrls = false`)

Every URL is reduced to a canonical key (`canonicalKey()`): the scheme key (10.3) with **noise parameters** removed and the remaining parameters sorted. Two URLs are duplicates when their keys are equal.

Noise parameters (`isNoise()`) never change what a page contains:
- campaign and click tracking: `utm_*`, `gclid`, `fbclid`, `msclkid` and similar
- session identifiers: `jsessionid`, `phpsessid`, `sessionid`, `session_id`, and `sid` when its value is a token of 16 or more letters and digits (a short `sid` is an identifier - a survey, a story)
- sort keys: `sort`, `sortby`, `sort_by`, `orderby`, `order_by`
- `order`, `dir` and `direction` - but only when the value is a direction such as `asc` or `desc`: `?dir=desc` sorts a listing, `?dir=/reports` opens another folder

```
queue /list?q=x             → key /list?q=x
queue /list?q=x&sort=date   → key /list?q=x          → DUPLICATE (a sort order added)
queue /list?q=x&page=2      → key /list?page=2&q=x   → NOT DUPLICATE (the next page)
```

Every other parameter is assumed to select content. Being wrong in that direction costs a duplicate visit; being wrong the other way silently skips content.

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
| `offsiteRedirects` | `LinkedHashMap<String, String>` | in-scope URL → the off-site URL it redirected to, when not followed |
| `errorCounts` | `LinkedHashMap<ErrorType, Integer>` | counts per error category |
| `networkErrorReasons` | `LinkedHashMap<String, Integer>` | breakdown of network error types |
| `visitedCount` | `int` | pages fetched successfully (all content types); failed fetches are not counted |
| `parsedCount` | `int` | pages successfully parsed (HTML + binary) |
| `docsCount` | `int` | binary documents (PDF, DOCX, etc.) parsed |
| `durationMs` | `long` | wall-clock time for the entire crawl |
| `stoppedAtMaxHits` | `int` | 0 if ran to completion; otherwise the `--max-hits` value that triggered the stop |

`LinkedHashMap` is used (rather than `HashMap`) to preserve insertion order, so results appear in the order pages were visited - which is the BFS/DFS order of the crawl.

### 11.2 `ErrorType` enum

- `NETWORK_ERROR` - DNS failure, connection refused, timeout, SSL error, unexpected HTTP status
- `BLOCKED` - HTTP 403, HTTP 429, or bot-protection page detected
- `PARSE_ERROR` - Jsoup failed to parse the HTML
- `SKIPPED_SIZE` - response body exceeded `--max-bytes`
- `SKIPPED_OFFSITE` - a redirect to another site was not followed (no `--allow-external`)
- `SKIPPED_TYPE` - (reserved for future use)

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

Both are Java records - immutable data carriers with no behaviour beyond `FileScanResult.totalMatches()`.

```java
public record FileMatch(int page, int line, int count, String snippet) {}
```

- `page` - 1-based page number for multi-page documents; 0 for plain text (no page structure)
- `line` - 1-based line number within the page; in paged documents, counted over non-blank lines (3.4)
- `count` - number of keyword occurrences starting on this line
- `snippet` - the line content (joined with the next line when a match continues there), cut to 120 characters around the match

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

Formats results and prints them to `System.out`. Six public methods - one text and one JSON variant for each of the three modes.

### 13.1 Web crawl text output - `printTextOutput()`

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

Blocked URLs are grouped by reason so a flood of 403s doesn't spam hundreds of lines (capped at 10 URLs per reason group). Redirects to other sites that were not followed are listed after them as `source -> target`, also capped at 10.

Every string in text output that comes from outside WebGrep - URLs, snippets, file paths, server error messages - passes through `ConsoleText.safe()` (14.4).

### 13.2 JSON output - `printJsonOutput()`

All JSON is built manually via `StringBuilder` rather than a JSON library. This avoids adding a dependency for a relatively simple output structure. Special characters in strings are escaped by `escapeJson()`, which handles `\`, `"`, newlines, carriage returns, tabs, and every other control character - below `0x20`, DEL, and the C1 range `0x80`-`0x9F`. DEL and C1 are allowed raw in JSON, but escaping them keeps the output safe to view in a terminal.

The JSON structure is:
```json
{
  "query": { "url", "depth", "keyword", "mode" },
  "stats": { "duration_ms", "total_matches", "pages_visited", "pages_parsed", "docs_parsed", "pages_blocked", "errors": { … } },
  "stopped_early": "…",          ← only if --max-hits triggered early stop
  "results": [ { "url", "count", "snippets": […] } ],
  "blocked": [ { "url", "reason" } ],
  "redirected_offsite": [ { "url", "target" } ]
}
```

### 13.3 Folder / file variants

`printFolderTextOutput()` and `printFileTextOutput()` follow the same pattern but show file paths and line numbers instead of URLs. The `hasPages` flag controls whether page numbers appear in the output - it is `true` only when at least one `FileMatch` has `page > 0` (i.e. when the extracted text carried page marks, meaning the source was a paged document like a PDF).

### 13.4 `formatDuration(long ms)`

```java
if (ms < 60_000) return String.format("%.2fs", ms / 1000.0);
return String.format("%dm %.2fs", minutes, seconds);
```

Short runs display as `4.23s`; longer runs display as `1m 32.00s`.

---

## 14. Utilities: `UrlUtils.java` & `ConsoleText.java`

**File:** `src/main/java/com/webgrep/utils/UrlUtils.java`

Static URL helpers used throughout the codebase.

### 14.1 `normalizeUrl(String urlString, String baseUrlString)`

Converts any URL (relative, protocol-relative, or absolute) to a canonical absolute `http://` or `https://` URL.

Steps in order:
1. **Control characters** (`cleanRaw()`), following the URL standard: leading and trailing spaces and C0 controls are trimmed, tabs and newlines are removed wherever they occur (an `href` wrapped across lines in the HTML source is still one URL), and every other C0 control, DEL and C1 control is percent-encoded. Link targets taken straight from markup - a meta-refresh target, a URL found in JSON - can therefore never carry an escape sequence to the queue, the fetcher or the terminal.
2. **Protocol-relative** (`//example.com/…`): prepend `https:` or `http:` based on the base URL's scheme.
3. **Non-http(s) schemes**: any explicit non-http scheme (`ftp:`, `javascript:`, `data:`, etc.) is rejected and returns `""`.
4. **Relative URLs**: resolved against `baseUrlString` using `new URL(base, relative)`.
5. **Normalisation**: lowercases scheme and host; removes default ports (80 for http, 443 for https); collapses `//` in paths to `/`.
6. **Fragment stripping**: fragments (`#section`) are naturally dropped by `URL.getPath()` / `URL.getQuery()`.

Returns `""` on any failure so callers can filter with `!url.isEmpty()`.

### 14.2 `isDocumentLink(String url)`

Returns `true` if the URL path (after stripping fragment and query) ends in a known document extension: `.pdf`, `.doc`, `.docx`, `.txt`, `.xlsx`, `.odt`, `.pptx`, `.csv`.

Used by `Crawler` to split links into `docLinksToEnqueue` (bypass depth limit) and `navLinks` (respect depth limit).

### 14.3 `isIgnoredLink(String url)`

Returns `true` for URLs that should never be fetched:

**Static assets:** `.css`, `.js`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.ico`, `.woff`, `.woff2`, `.ttf`, `.otf`, `.mp3`, `.mp4`, `.wav`, `.avi`, `.mov`, `.wmv`, `.zip`, `.rar`, `.7z`, `.tar.gz`

**Social share redirect URLs:** `googleads`, `doubleclick`, `facebook.com/sharer`, `twitter.com/intent/tweet`, `linkedin.com/share`, `pinterest.com/pin`

**Taxonomy pages** (typically thousands of near-duplicate listing pages): `/tag/`, `/tags/`, `/author/`

Document extensions (PDF, DOCX, etc.) are explicitly **not** ignored - `isDocumentLink()` is called first and returns `false` from `isIgnoredLink()` if it matches.

### 14.4 `ConsoleText.safe(String text)`

**File:** `src/main/java/com/webgrep/utils/ConsoleText.java`

Page text, link targets, server error messages and file names all come from outside WebGrep. Printed raw, a control character in any of them reaches the terminal as an instruction rather than text: `ESC ] 0 ; title BEL` retitles the window, `ESC [ 2 J` clears the screen, and other sequences move the cursor to hide output or, in some terminals, write to the clipboard. `safe()` replaces every C0 control, DEL and C1 control with a visible `\xNN` escape - so a file name containing one stays recognisable - and returns the string unchanged when there is nothing to escape. Every externally sourced string printed as text goes through it: progress lines, prompts, warnings, and all of `ReportWriter`'s text output.

---

## 15. Tests

**Directory:** `src/test/java/com/webgrep/`, plus `src/test/java/com/webgrep/core/` for tests that need package-private access.

| File | What it tests |
|---|---|
| `AppIntegrationTest.java` | End-to-end through `Main.main()`: file and folder modes, text and JSON output, all match modes, `--max-bytes`, `--max-hits`, Windows line endings, multiple matches per line, PDF page numbers from a generated PDF, a folder containing a named pipe |
| `ContentExtractorTest.java` | HTML text extraction, Tika binary extraction, link extraction, PDF page marks (generated with PDFBox, including a one-page PDF), page and line numbers of a phrase split across PDF lines |
| `MainTest.java` | `UrlUtils` (normalizeUrl, control characters, isIgnoredLink), `ConsoleText`, `MatchEngine` (counts, positions, snippets, whitespace tolerance, fuzzy word boundaries and typo snippets, text without spaces), `CrawlResult` accumulation, `Main.findLineMatches` (pages, line numbers, phrases across lines, snippets around the match), `Main.listFiles` (symlinks, named pipes, symlinked root), `CliOptions` parsing and validation |
| `ReportWriterTest.java` | JSON escaping (including DEL and C1), duration formatting, blocked URL grouping, off-site redirect listing, control characters escaped in text output, stopped-early output, text/JSON ordering parity |
| `UrlDeduplicatorTest.java` | Dedup rules: noise parameters (tracking, sessions, sort keys and directions), content parameters such as pagination, parameter order, `--all-urls` mode, scheme normalisation |
| `core/CookieJarTest.java` | Cookie rules: host-only vs domain cookies, refused domains, `Secure`, paths and default path, replacement and deletion, `Max-Age` vs `Expires`, date formats, browser round-trip |
| `core/CrawlerTest.java` | Meta-refresh parsing; crawls against a local HTTP server: off-site redirects, cookies across redirects, seed redirects moving the scope, meta-refresh depth, the page budget with broken links, pagination |
| `core/PlaywrightRendererTest.java` | Rendered-text extraction without a browser, browser folder order |

All tests use JUnit 4. There are no mocks. `AppIntegrationTest` writes real temp files and calls `Main.main()` directly with stdout redirected to a `ByteArrayOutputStream` for assertions. `CrawlerTest` starts the JDK's built-in `com.sun.net.httpserver.HttpServer` on the loopback address and reaches it as both `localhost` and `127.0.0.1`, which the crawler treats as two different sites. The tests in `com.webgrep.core` live in that package so they can reach package-private members.

---

## 16. Build & deployment

**`pom.xml`:** Maven build file.

- `maven.compiler.release` 17: compiles against the Java 17 API itself, so code that needs a newer JDK fails the build instead of failing at runtime on Java 17.
- `maven-shade-plugin` creates a fat JAR (`WebGrep-{version}.jar`) containing all dependencies.
- The `ServicesResourceTransformer` merges `META-INF/services/` files so Tika's format-detection plugins (registered via SPI) are all preserved in the shaded JAR.
- The dependencies' `NOTICE` files are merged (`ApacheNoticeResourceTransformer`) and their `LICENSE` and `DEPENDENCIES` files appended (`AppendingTransformer`), instead of keeping whichever copy happens to be shaded last.
- Signature files (`.SF`, `.DSA`, `.RSA`) are excluded - they become invalid after shading and would cause JAR verification failures. So are the dependencies' `module-info.class` files: the fat JAR runs on the class path as one unnamed module, where module descriptors do not apply.
- `commons-logging` is excluded from Tika's dependencies: `jcl-over-slf4j`, which Tika also brings, provides the same classes and routes them to SLF4J.

**CI:** `.github/workflows/build.yml` runs `mvn verify` on Java 17 and 21 for every push and pull request.

**Dockerfile:** Multi-stage build.
- Stage 1: Maven on `eclipse-temurin:17` compiles and packages the JAR.
- Stage 2: `eclipse-temurin:17-jre-alpine` copies only the shaded JAR from stage 1 and runs it as an unprivileged `webgrep` user.
- `.dockerignore` keeps everything but `pom.xml` and `src/` out of the build context.
- The image is not small: the fat JAR alone is over 250 MB, mostly Playwright's driver bundle (a Node.js runtime for every supported platform) and Tika's parsers.

**Local deployment:**
```bash
cp target/WebGrep-1.3.0.jar ~/.local/share/webgrep/WebGrep.jar
```
The shell wrapper at `~/.local/bin/webgrep` points to this JAR location.
