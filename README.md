# WebGrep

WebGrep is a high-performance CLI crawler and keyword search tool. It searches for keywords across websites and automatically parses binary documents — including **PDF, DOCX, TXT, and many other file formats** — discovered during the crawl, using Apache Tika for text extraction.

**Tech stack:** Java 17+, Maven, [Jsoup](https://jsoup.org/) (HTML fetching/parsing), [Apache Tika](https://tika.apache.org/) (document text extraction), JUnit 4 (tests).

![WebGrep demo](images/demo.gif)

### Architecture
WebGrep is designed with a modular architecture for high performance and maintainability:
- **CliOptions**: Handles advanced argument parsing and strict input validation.
- **Crawler**: Manages the multi-level crawl queue, domain constraints, body size limits, and configurable politeness delays. Maintains a session cookie jar across all requests, and automatically retries on HTTP 429 (rate limited) with exponential backoff before giving up. Displays a live progress indicator during the crawl.
- **ContentExtractor**: Orchestrates intelligent text extraction from HTML pages (via Jsoup) and binary documents like PDF/DOCX (via Apache Tika), with a 30-second timeout per document to prevent hangs on corrupt or oversized files.
- **MatchEngine**: Executes pluggable matching strategies including case-insensitive, exact, and fuzzy (Levenshtein) searches with full Unicode and diacritic support. Regex patterns are cached per run for performance.
- **ReportWriter**: Generates human-readable text summaries or structured JSON for automation.

### Depth Definition
- **Depth 0**: Fetches and searches only the provided seed URL. No links are followed.
- **Depth 1**: Fetches the seed URL, then follows all links found on that page (HTML links, PDF links, document links, etc.).
- **Depth N**: Continues recursively, following links up to N hops from the seed.

Each URL is visited at most once per run. By default, URLs that look like navigation variants of an already-visited page are skipped: if `list?id=1` was visited, then `list?id=1&sort=asc` is treated as a variant and skipped — but `list?id=2` has a different value and is treated as new content. Use `--all-urls` to disable this.

### Usage
```bash
java -jar WebGrep.jar -u <URL> -k <keyword> [options]
```

#### Options:
- `-u, --url <URL>`: The starting URL (required).
- `-k, --keyword <word>`: The keyword to search for (required).
- `-d, --depth <n>`: Maximum crawl depth (default: 1).
- `-m, --mode <mode>`: Match strategy (`default`, `exact`, `fuzzy`).
- `-p, --max-pages <n>`: Stop after visiting N pages (default: 5000).
- `-b, --max-bytes <n>`: Skip files larger than N bytes (default: 10MB).
- `-t, --timeout-ms <n>`: Network timeout per request in milliseconds (default: 20000).
- `-r, --delay-ms <n>`: Delay between requests in milliseconds (default: 100).
- `-n, --max-hits <n>`: Stop crawling as soon as n total matches have been found (default: 0 = no limit). Pairs well with `--dfs` to surface deeply buried results quickly, or with BFS (default) to find the first N most prominent matches.
- `-a, --all-urls`: Disable smart URL deduplication. By default, if `page?id=1` was visited, `page?id=1&sort=asc` is treated as a navigation variant and skipped. Use this flag to visit every URL regardless — useful when you want all sort/filter/pagination variants crawled.
- `-s, --dfs`: Use depth-first search instead of the default breadth-first search. BFS explores the site level by level (all pages at depth 1 before depth 2); DFS follows each link chain as deep as possible before backtracking. DFS can find deeply buried documents faster; BFS gives more representative coverage of the whole site.
- `-e, --allow-external`: Allow the crawler to follow links outside the starting domain.
- `-i, --insecure`: Disable SSL certificate verification. Use with caution — this bypasses all TLS validation.
- `-o, --output <format>`: Output format (`text` or `json`).
- `-h, --help`: Show help message.

### Matching Modes
- **Default**: Case-insensitive matching with Unicode and diacritic support. `cafe` matches `Café`, `CAFE`, `café`. If no direct match is found, a simplified (diacritic-stripped, punctuation-removed) fallback pass is attempted.
- **Exact**: Strict case-sensitive literal matching. `hello` does not match `Hello`.
- **Fuzzy**: First tries a normalized (diacritic-stripped, punctuation-removed) substring match. If nothing is found, splits the text into words and accepts any word within a Levenshtein edit distance of 1 (for keywords of 4 characters or fewer) or 2 (for longer keywords). This catches common typos and minor spelling variations.

### Examples
**Basic search:**
```bash
java -jar WebGrep.jar -u https://example.com -k domain
```

**Deep crawl including linked PDFs and documents:**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 2
```

**Search directly inside a PDF:**
```bash
java -jar WebGrep.jar -u https://example.com/report.pdf -k "revenue" -d 0
```

**JSON output:**
```bash
java -jar WebGrep.jar -u https://example.com -k domain -o json
```

**Fast crawl with no delay between requests:**
```bash
java -jar WebGrep.jar -u https://example.com -k topic -d 2 -r 0
```

**Crawl all sort/filter variants of a listing page:**
```bash
java -jar WebGrep.jar -u https://example.com/listings -k topic -d 1 --all-urls
```

**Stop after the first 5 matches (BFS — finds most prominent results first):**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 2 -n 5
```

**Stop after the first 5 matches (DFS — dives deep before backtracking):**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 3 -s -n 5
```

### Document Support
WebGrep automatically detects and extracts text from any linked document using Apache Tika. Supported formats include:
- **PDF** (`.pdf`)
- **Word** (`.doc`, `.docx`)
- **Plain text** (`.txt`)
- **And many more** — Tika supports 100+ formats including ODT, RTF, EPUB, XLS, XLSX, PPT, PPTX, and more. Any file whose link passes the URL filter will be parsed.

Each binary document is parsed with a **30-second timeout**. If parsing takes longer (e.g. a corrupt or malformed file), it is skipped and the raw bytes are used as a fallback.

Files that are never fetched or parsed (images, video, CSS, JS, fonts, archives, social share links) are filtered by URL before any request is made.

### Sample Output (JSON)
```json
{
  "query": {
    "url": "https://example.com",
    "keyword": "domain",
    "depth": 1,
    "mode": "default"
  },
  "stats": {
    "duration_ms": 1243,
    "total_matches": 13,
    "pages_visited": 1,
    "pages_parsed": 1,
    "docs_parsed": 0,
    "pages_blocked": 0,
    "errors": {
      "network_error": 0,
      "blocked": 0,
      "parse_error": 0,
      "skipped_size": 0,
      "skipped_type": 0
    }
  },
  "results": [
    { "url": "https://example.com/", "count": 13 }
  ],
  "blocked": [
  ]
}
```

#### JSON Fields Reference
| Field | Meaning |
|---|---|
| `stats.duration_ms` | Wall-clock time for the entire crawl in milliseconds |
| `stats.docs_parsed` | Number of binary documents (PDF, DOCX, etc.) parsed during the crawl |
| `stopped_early` | Present only when `--max-hits` triggered an early exit; describes the limit that was reached |
| `stats.errors.network_error` | Request failed (DNS failure, connection refused, timeout, non-403/429 HTTP error) |
| `stats.errors.network_error_reasons` | Breakdown of network errors by cause (e.g. `Timeout: 30, HTTP 404: 5`) |
| `stats.errors.blocked` | Server returned 403 or 429, or a bot-protection challenge was detected |
| `stats.errors.skipped_size` | File exceeded `--max-bytes` and was not parsed |
| `stats.errors.parse_error` | Reserved for future use |
| `stats.errors.skipped_type` | Reserved for future use |

### Limitations
- **JavaScript**: WebGrep processes static HTML only. It does not execute JavaScript. Pages that render content client-side (SPAs) may not be fully indexed.
- **Bot Protection**: JavaScript-based challenges (e.g. Cloudflare Managed Challenges) cannot be bypassed. They are detected by known challenge page signatures and reported under `blocked`.
- **Robots.txt**: The tool does not parse `robots.txt`. Use `--delay-ms` to be polite to servers.
- **Authentication**: No support for login sessions or HTTP Basic Auth. Session cookies set by the server are automatically maintained across requests, but authenticated areas requiring a login form cannot be accessed.

### Build
Requires Java 17+ and Maven.
```bash
mvn package
```
Produces `target/WebGrep-1.0.0.jar` — a self-contained fat JAR with all dependencies included. No additional installation required.

---

Written by and belongs to Simon D.  
Free to use for personal and educational purposes.  
For commercial use please contact me at simon.d.dev@proton.me.  
