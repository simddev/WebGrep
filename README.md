# WebGrep

WebGrep is a high-performance CLI keyword search tool with three modes: **web crawl** (recursively search a website and its linked documents), **local file** (search a single file offline), and **local folder** (recursively search all files in a directory). All modes use Apache Tika for text extraction from **PDF, DOCX, TXT, and 100+ other formats**, and report exact page and line numbers for every match.

**Tech stack:** Java 17+, Maven, [Jsoup](https://jsoup.org/) (HTML fetching/parsing), [Apache Tika](https://tika.apache.org/) (document text extraction), JUnit 4 (tests).

![WebGrep demo](images/demo.gif)

---

## Getting Started

### Option 1 — Native JAR (requires Java 17+)
```bash
mvn package
java -jar target/WebGrep-1.0.0.jar -u https://example.com -k "your keyword"
```

### Option 2 — Docker (no Java required)
```bash
docker build -t webgrep .
docker run --rm webgrep -u https://example.com -k "your keyword"
```

For local files and folders, mount the host path into the container:
```bash
# Single file
docker run --rm -v /path/to/file.pdf:/data/file.pdf webgrep -f /data/file.pdf -k revenue

# Entire folder
docker run --rm -v /path/to/documents:/data webgrep -F /data -k confidential
```

The Docker image uses a multi-stage build — Maven compiles the JAR in the build stage, only the lightweight Alpine JRE is included in the final image (~90 MB).

---

## Usage

WebGrep has three input modes. Exactly one must be specified per run:

```bash
# Web crawl mode
java -jar WebGrep.jar -u <URL> -k <keyword> [options]

# Local file mode
java -jar WebGrep.jar -f <path> -k <keyword> [options]

# Local folder mode
java -jar WebGrep.jar -F <path> -k <keyword> [options]
```

### Options

**Input (exactly one required):**
- `-u, --url <URL>`: The starting URL for a web crawl.
- `-f, --file <path>`: Search a single local file. Supports all formats Apache Tika understands (PDF, DOCX, TXT, and more).
- `-F, --folder <path>`: Recursively search all files in a local directory. Results are grouped by file.

**Matching:**
- `-k, --keyword <word>`: The keyword to search for (required).
- `-m, --mode <mode>`: Match strategy: `default`, `exact`, or `fuzzy`.

**Web crawl controls:**
- `-d, --depth <n>`: Maximum crawl depth (default: 1). See [Depth Definition](#depth-definition) below.
- `-p, --max-pages <n>`: Stop after visiting N pages (default: 5000).
- `-t, --timeout-ms <n>`: Network timeout per request in milliseconds (default: 20000).
- `-r, --delay-ms <n>`: Delay between requests in milliseconds (default: 100).
- `-n, --max-hits <n>`: Stop as soon as N total matches are found (default: 0 = no limit). Pairs well with `--dfs` to surface deeply buried results quickly, or with BFS (default) to find the first N most prominent matches.
- `-a, --all-urls`: Disable smart URL deduplication. By default, `page?id=1&sort=asc` is treated as a variant of `page?id=1` and skipped. Use this flag to visit every URL regardless.
- `-s, --dfs`: Use depth-first search instead of the default breadth-first. BFS covers the site level by level; DFS follows each link chain as deep as possible before backtracking.
- `-e, --allow-external`: Follow links outside the starting domain.
- `-i, --insecure`: Disable SSL certificate verification. Use with caution.

**General:**
- `-b, --max-bytes <n>`: Skip files larger than N bytes (default: 10MB). Applies to web downloads and local files.
- `-o, --output <format>`: Output format: `text` (default) or `json`.
- `-h, --help`: Show help message.

### Matching Modes

- **Default**: Case-insensitive with Unicode and diacritic support. `cafe` matches `Café`, `CAFE`, `café`. If no direct match is found, a diacritic-stripped, punctuation-removed fallback pass is attempted.
- **Exact**: Strict case-sensitive literal match. `hello` does not match `Hello`.
- **Fuzzy**: First tries a normalised substring match. If that fails, splits the text into words and accepts any word within Levenshtein edit distance 1 (for keywords ≤ 4 characters) or 2 (for longer keywords). Catches common typos and spelling variants.

### Depth Definition

Applies to web crawl mode only.

- **Depth 0**: Fetches and searches only the seed URL. No links are followed.
- **Depth 1**: Fetches the seed URL, then follows all links found on that page.
- **Depth N**: Continues recursively, following links up to N hops from the seed.

Each URL is visited at most once per run. By default, URLs that look like navigation variants of an already-visited page are skipped — `list?id=1&sort=asc` is treated as a variant of `list?id=1`, but `list?id=2` is treated as new content. Use `--all-urls` to disable this.

---

## Examples

### Web Crawl

**Basic search:**
```bash
java -jar WebGrep.jar -u https://example.com -k domain
```

**Deep crawl including linked PDFs and documents:**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 2
```

**Search directly inside a remote PDF:**
```bash
java -jar WebGrep.jar -u https://example.com/report.pdf -k "revenue" -d 0
```

**JSON output:**
```bash
java -jar WebGrep.jar -u https://example.com -k domain -o json
```

**Fast crawl with no delay:**
```bash
java -jar WebGrep.jar -u https://example.com -k topic -d 2 -r 0
```

**Crawl all sort/filter variants of a listing page:**
```bash
java -jar WebGrep.jar -u https://example.com/listings -k topic -d 1 --all-urls
```

**Stop after the first 5 matches (BFS — most prominent results first):**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 2 -n 5
```

**Stop after the first 5 matches (DFS — dives deep before backtracking):**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 3 -s -n 5
```

### Local File

**Search a PDF or plain text file:**
```bash
java -jar WebGrep.jar -f /path/to/report.pdf -k "revenue"
```

**Case-sensitive search in a DOCX file:**
```bash
java -jar WebGrep.jar -f /path/to/contract.docx -k "Clause 4.2" -m exact
```

**JSON output:**
```bash
java -jar WebGrep.jar -f /path/to/report.pdf -k "revenue" -o json
```

### Local Folder

**Search all files in a folder recursively:**
```bash
java -jar WebGrep.jar -F /path/to/documents -k "confidential"
```

**Skip files larger than 5 MB:**
```bash
java -jar WebGrep.jar -F /path/to/documents -k "invoice" -b 5242880
```

**JSON output:**
```bash
java -jar WebGrep.jar -F /path/to/documents -k "confidential" -o json
```

---

## Document Support

All three modes use Apache Tika for text extraction. Supported formats include:

- **PDF** (`.pdf`)
- **Word** (`.doc`, `.docx`)
- **Plain text** (`.txt`)
- **And many more** — Tika supports 100+ formats including ODT, RTF, EPUB, XLS, XLSX, PPT, PPTX, and more.

Each file is parsed with a **30-second timeout**. If parsing takes longer (e.g. a corrupt or malformed file), it is skipped and the raw bytes are used as a UTF-8 fallback.

**Web crawl mode:** static assets (images, video, CSS, JS, fonts, archives, social share links) are filtered by URL before any request is made.

**File and folder modes:** every file is passed to Tika regardless of extension. Files exceeding `--max-bytes` are skipped and counted in the summary.

---

## Sample Output (JSON)

### Web Crawl
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

### Local File
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

### Local Folder
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
    "files_failed": 0,
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

### JSON Fields Reference
| Field | Meaning |
|---|---|
| `stats.duration_ms` | Wall-clock time for the entire run in milliseconds |
| `stats.docs_parsed` | *(web crawl)* Binary documents (PDF, DOCX, etc.) parsed during the crawl |
| `stopped_early` | *(web crawl)* Present only when `--max-hits` triggered an early stop |
| `stats.errors.network_error` | *(web crawl)* Requests that failed (DNS, timeout, connection refused, non-403/429 HTTP errors) |
| `stats.errors.network_error_reasons` | *(web crawl)* Breakdown by cause (e.g. `Timeout: 30, HTTP 404: 5`) |
| `stats.errors.blocked` | *(web crawl)* Pages returning 403/429 or detected bot-protection challenges |
| `stats.errors.skipped_size` | *(web crawl)* Files that exceeded `--max-bytes` and were not parsed |
| `stats.files_skipped` | *(folder)* Files that exceeded `--max-bytes` and were not scanned |
| `stats.files_failed` | *(folder)* Files that could not be read or parsed (permission error, corrupt file, etc.) |
| `matches[].page` | *(file/folder)* Page number (1-based); omitted when the file has no page structure |
| `matches[].line` | *(file/folder)* Line number within the page (1-based) |
| `matches[].count` | *(file/folder)* Keyword occurrences on that line |
| `matches[].snippet` | *(file/folder)* Trimmed line content, truncated to 120 characters |

---

## Architecture

WebGrep is designed with a modular architecture for performance and maintainability:

- **CliOptions**: Parses and validates all command-line arguments. Enforces mutual exclusion between `--url`, `--file`, and `--folder`.
- **Crawler**: Manages the multi-level crawl queue, domain scoping, body size limits, and configurable politeness delays. Maintains a session cookie jar across requests, and retries automatically on HTTP 429 with exponential backoff. Displays a live progress indicator during the crawl.
- **ContentExtractor**: Extracts searchable text from HTML pages via Jsoup, and from binary documents via Apache Tika with a 30-second timeout per file to prevent hangs on corrupt or oversized content.
- **MatchEngine**: Pluggable matching strategies (case-insensitive, exact, fuzzy/Levenshtein) with full Unicode and diacritic support. Compiled regex patterns are cached per keyword/mode pair for performance.
- **ReportWriter**: Renders results as human-readable text or structured JSON. Covers all three input modes.

---

## Limitations

- **JavaScript**: WebGrep processes static HTML only. Pages that render content client-side (SPAs) may not be fully indexed.
- **Bot Protection**: JavaScript-based challenges (e.g. Cloudflare Managed Challenges) cannot be bypassed. They are detected and reported under `blocked`.
- **Robots.txt**: Not parsed. Use `--delay-ms` to be polite to servers.
- **Authentication**: No support for login forms or HTTP Basic Auth. Session cookies set by the server are maintained across requests automatically.

---

Written by and belongs to Simon D.  
Free to use for personal and educational purposes.  
For commercial use please contact me at simon.d.dev@proton.me.  
