# WebGrep

WebGrep is a high-performance CLI keyword search tool with three modes: **web crawl** (recursively search a website), **local file** (search a single file offline), and **local folder** (recursively search all files in a directory). All modes support Apache Tika text extraction from **PDF, DOCX, TXT, and 100+ other formats**, and report exact page and line numbers for every match.

**Tech stack:** Java 17+, Maven, [Jsoup](https://jsoup.org/) (HTML fetching/parsing), [Apache Tika](https://tika.apache.org/) (document text extraction), JUnit 4 (tests).

![WebGrep demo](images/demo.gif)

### Architecture
WebGrep is designed with a modular architecture for high performance and maintainability:
- **CliOptions**: Handles advanced argument parsing and strict input validation. Enforces mutual exclusion between `--url`, `--file`, and `--folder`.
- **Crawler**: Manages the multi-level crawl queue, domain constraints, body size limits, and configurable politeness delays. Maintains a session cookie jar across all requests, and automatically retries on HTTP 429 (rate limited) with exponential backoff before giving up. Displays a live progress indicator during the crawl.
- **ContentExtractor**: Orchestrates intelligent text extraction from HTML pages (via Jsoup) and binary documents like PDF/DOCX (via Apache Tika), with a 30-second timeout per document to prevent hangs on corrupt or oversized files.
- **MatchEngine**: Executes pluggable matching strategies including case-insensitive, exact, and fuzzy (Levenshtein) searches with full Unicode and diacritic support. Regex patterns are cached per run for performance.
- **ReportWriter**: Generates human-readable text summaries or structured JSON for automation. Covers all three input modes (web crawl, single file, folder scan).

### Depth Definition
- **Depth 0**: Fetches and searches only the provided seed URL. No links are followed.
- **Depth 1**: Fetches the seed URL, then follows all links found on that page (HTML links, PDF links, document links, etc.).
- **Depth N**: Continues recursively, following links up to N hops from the seed.

Each URL is visited at most once per run. By default, URLs that look like navigation variants of an already-visited page are skipped: if `list?id=1` was visited, then `list?id=1&sort=asc` is treated as a variant and skipped — but `list?id=2` has a different value and is treated as new content. Use `--all-urls` to disable this.

### Usage
WebGrep has three input modes. Exactly one must be specified:

```bash
# Web crawl mode
java -jar WebGrep.jar -u <URL> -k <keyword> [options]

# Local file mode
java -jar WebGrep.jar -f <path> -k <keyword> [options]

# Local folder mode
java -jar WebGrep.jar -F <path> -k <keyword> [options]
```

#### Options:

**Input (exactly one required):**
- `-u, --url <URL>`: The starting URL for a web crawl.
- `-f, --file <path>`: Search a single local file instead of crawling the web. Supports all formats that Apache Tika understands (PDF, DOCX, TXT, and more).
- `-F, --folder <path>`: Recursively search all files in a local directory. Each file is scanned independently; results are grouped by file.

**Matching:**
- `-k, --keyword <word>`: The keyword to search for (required).
- `-m, --mode <mode>`: Match strategy (`default`, `exact`, `fuzzy`).

**Web crawl controls:**
- `-d, --depth <n>`: Maximum crawl depth (default: 1).
- `-p, --max-pages <n>`: Stop after visiting N pages (default: 5000).
- `-t, --timeout-ms <n>`: Network timeout per request in milliseconds (default: 20000).
- `-r, --delay-ms <n>`: Delay between requests in milliseconds (default: 100).
- `-n, --max-hits <n>`: Stop crawling as soon as n total matches have been found (default: 0 = no limit). Pairs well with `--dfs` to surface deeply buried results quickly, or with BFS (default) to find the first N most prominent matches.
- `-a, --all-urls`: Disable smart URL deduplication. By default, if `page?id=1` was visited, `page?id=1&sort=asc` is treated as a navigation variant and skipped. Use this flag to visit every URL regardless — useful when you want all sort/filter/pagination variants crawled.
- `-s, --dfs`: Use depth-first search instead of the default breadth-first search. BFS explores the site level by level (all pages at depth 1 before depth 2); DFS follows each link chain as deep as possible before backtracking. DFS can find deeply buried documents faster; BFS gives more representative coverage of the whole site.
- `-e, --allow-external`: Allow the crawler to follow links outside the starting domain.
- `-i, --insecure`: Disable SSL certificate verification. Use with caution — this bypasses all TLS validation.

**General:**
- `-b, --max-bytes <n>`: Skip files larger than N bytes (default: 10MB). Applies to both web downloads and local files.
- `-o, --output <format>`: Output format (`text` or `json`).
- `-h, --help`: Show help message.

### Matching Modes
- **Default**: Case-insensitive matching with Unicode and diacritic support. `cafe` matches `Café`, `CAFE`, `café`. If no direct match is found, a simplified (diacritic-stripped, punctuation-removed) fallback pass is attempted.
- **Exact**: Strict case-sensitive literal matching. `hello` does not match `Hello`.
- **Fuzzy**: First tries a normalized (diacritic-stripped, punctuation-removed) substring match. If nothing is found, splits the text into words and accepts any word within a Levenshtein edit distance of 1 (for keywords of 4 characters or fewer) or 2 (for longer keywords). This catches common typos and minor spelling variations.

### Examples

#### Web Crawl
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

#### Local File
**Search a plain text or PDF file:**
```bash
java -jar WebGrep.jar -f /path/to/report.pdf -k "revenue"
```

**Case-sensitive search in a DOCX file:**
```bash
java -jar WebGrep.jar -f /path/to/contract.docx -k "Clause 4.2" -m exact
```

**JSON output from a local file:**
```bash
java -jar WebGrep.jar -f /path/to/report.pdf -k "revenue" -o json
```

#### Local Folder
**Search all files in a folder recursively:**
```bash
java -jar WebGrep.jar -F /path/to/documents -k "confidential"
```

**Search with a file size limit (skip files larger than 5 MB):**
```bash
java -jar WebGrep.jar -F /path/to/documents -k "invoice" -b 5242880
```

**JSON output from a folder scan:**
```bash
java -jar WebGrep.jar -F /path/to/documents -k "confidential" -o json
```

### Document Support
All three input modes use Apache Tika for text extraction. Supported formats include:
- **PDF** (`.pdf`)
- **Word** (`.doc`, `.docx`)
- **Plain text** (`.txt`)
- **And many more** — Tika supports 100+ formats including ODT, RTF, EPUB, XLS, XLSX, PPT, PPTX, and more.

Each document is parsed with a **30-second timeout** per file. If parsing takes longer (e.g. a corrupt or malformed file), it is skipped and the raw bytes are used as a UTF-8 fallback.

**Web crawl mode:** only files whose URL passes the link filter are fetched — static assets (images, video, CSS, JS, fonts, archives, social share links) are skipped before any request is made.

**File and folder modes:** every file in the given path is passed to Tika regardless of extension. Files exceeding `--max-bytes` are skipped and counted in the summary.

### Sample Output (JSON)

#### Web Crawl
```json
{
  "query": {
    "url": "https://example.com",
    "depth": 1,
    "keyword": "domain",
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
  "blocked": []
}
```

#### Local File
```json
{
  "query": {
    "file": "/path/to/report.pdf",
    "keyword": "revenue",
    "mode": "default"
  },
  "stats": {
    "duration_ms": 84,
    "total_matches": 3
  },
  "matches": [
    { "page": 1, "line": 4,  "count": 1, "snippet": "Total revenue for the year was $4.2M" },
    { "page": 2, "line": 11, "count": 2, "snippet": "Revenue growth and revenue targets exceeded" }
  ]
}
```

> For plain text files (no page structure), the `page` field is omitted from each match object.

#### Local Folder
```json
{
  "query": {
    "folder": "/path/to/documents",
    "keyword": "confidential",
    "mode": "default"
  },
  "stats": {
    "duration_ms": 312,
    "files_scanned": 5,
    "files_skipped": 1,
    "files_with_matches": 2,
    "total_matches": 4
  },
  "results": [
    {
      "file": "/path/to/documents/contract.docx",
      "total_matches": 3,
      "matches": [
        { "line": 2, "count": 1, "snippet": "CONFIDENTIAL — Do not distribute" },
        { "line": 17, "count": 2, "snippet": "This document is confidential and confidential use only" }
      ]
    },
    {
      "file": "/path/to/documents/notes.txt",
      "total_matches": 1,
      "matches": [
        { "line": 5, "count": 1, "snippet": "Mark as confidential before sending" }
      ]
    }
  ]
}
```

#### JSON Fields Reference
| Field | Meaning |
|---|---|
| `stats.duration_ms` | Wall-clock time for the entire run in milliseconds |
| `stats.docs_parsed` | *(web crawl)* Number of binary documents (PDF, DOCX, etc.) parsed during the crawl |
| `stopped_early` | *(web crawl)* Present only when `--max-hits` triggered an early exit; describes the limit reached |
| `stats.errors.network_error` | *(web crawl)* Request failed (DNS failure, connection refused, timeout, non-403/429 HTTP error) |
| `stats.errors.network_error_reasons` | *(web crawl)* Breakdown of network errors by cause (e.g. `Timeout: 30, HTTP 404: 5`) |
| `stats.errors.blocked` | *(web crawl)* Server returned 403 or 429, or a bot-protection challenge was detected |
| `stats.errors.skipped_size` | *(web crawl)* File exceeded `--max-bytes` and was not parsed |
| `stats.errors.parse_error` | Reserved for future use |
| `stats.errors.skipped_type` | Reserved for future use |
| `stats.files_skipped` | *(folder)* Files that exceeded `--max-bytes` and were not scanned |
| `matches[].page` | *(file/folder)* Page number (1-based); omitted for plain-text files with no page structure |
| `matches[].line` | *(file/folder)* Line number within the page (1-based) |
| `matches[].count` | *(file/folder)* Number of keyword occurrences on that line |
| `matches[].snippet` | *(file/folder)* Trimmed line content, truncated to 120 characters |

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

### Docker
No Java installation required. Build the image once from the provided `Dockerfile`:
```bash
docker build -t webgrep .
```

Then run any mode using `docker run`:
```bash
# Web crawl
docker run --rm webgrep -u https://example.com -k domain

# Local file — mount the file into the container under /data
docker run --rm -v /path/to/file.pdf:/data/file.pdf webgrep -f /data/file.pdf -k revenue

# Local folder — mount the folder into the container under /data
docker run --rm -v /path/to/documents:/data webgrep -F /data -k confidential
```

The image uses a multi-stage build: Maven compiles the JAR in a build stage, and only the lightweight Alpine JRE is included in the final image (~90 MB).

---

Written by and belongs to Simon D.  
Free to use for personal and educational purposes.  
For commercial use please contact me at simon.d.dev@proton.me.  
