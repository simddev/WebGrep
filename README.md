# WebGrep

WebGrep is a high-performance CLI crawler and keyword search tool. It searches for keywords across websites and automatically parses binary documents — including **PDF, DOCX, TXT, and other file formats** — discovered during the crawl, using Apache Tika for text extraction.

### Architecture
WebGrep is designed with a modular architecture for high performance and maintainability:
- **CliOptions**: Handles advanced argument parsing and strict input validation.
- **Crawler**: Manages the multi-level crawl queue, domain constraints, body size limits, and configurable politeness delays.
- **ContentExtractor**: Orchestrates intelligent text extraction from HTML pages (via Jsoup) and binary documents like PDF/DOCX (via Apache Tika), with a 30-second timeout per document to prevent hangs on corrupt files.
- **MatchEngine**: Executes pluggable matching strategies including case-insensitive, exact, and fuzzy (Levenshtein) searches with full Unicode and diacritic support.
- **ReportWriter**: Generates human-readable text summaries or structured JSON for automation.

### Depth Definition
- **Depth 0**: Scans only the provided seed URL.
- **Depth 1**: Scans the seed URL and all immediate links discovered on that page (including linked PDFs and documents).
- **Depth N**: Continues recursively up to N levels.

### Usage
```bash
java -jar WebGrep.jar --url <URL> --keyword <keyword> [options]
```

#### Options:
- `-u, --url <URL>`: The starting URL (required).
- `-k, --keyword <word>`: The keyword to search for (required).
- `-d, --depth <n>`: Maximum crawl depth (default: 1).
- `-m, --mode <mode>`: Match strategy (`default`, `exact`, `fuzzy`).
- `-p, --max-pages <n>`: Stop after crawling N pages (default: 5000).
- `-b, --max-bytes <n>`: Skip files larger than N bytes (default: 10MB).
- `-t, --timeout-ms <n>`: Network timeout per request in milliseconds (default: 20000).
- `--delay-ms <n>`: Delay between requests in milliseconds (default: 100).
- `-e, --allow-external`: Allow the crawler to follow links outside the starting domain.
- `-i, --insecure`: Disable SSL certificate verification (use with caution).
- `-o, --output <format>`: Output format (`text` or `json`).
- `-h, --help`: Show help message.

### Matching Modes
- **Default**: Case-insensitive matching with Unicode and diacritic support (e.g. `cafe` matches `Café`).
- **Exact**: Strict case-sensitive literal matching.
- **Fuzzy**: Normalizes diacritics, ignores punctuation, and applies Levenshtein distance to catch typos and close variations.

### Examples
**Basic search:**
```bash
java -jar WebGrep.jar -u https://example.com -k domain
```

**Search across a site including linked PDFs and documents:**
```bash
java -jar WebGrep.jar -u https://example.com -k "annual report" -d 2
```

**Search directly inside a PDF:**
```bash
java -jar WebGrep.jar -u https://example.com/report.pdf -k "revenue" -d 0
```

**JSON output with detailed metrics:**
```bash
java -jar WebGrep.jar -u https://example.com -k domain -o json
```

**Fast crawl with no delay:**
```bash
java -jar WebGrep.jar -u https://example.com -k topic -d 2 --delay-ms 0
```

### Document Support
WebGrep automatically detects and searches the following file types when encountered during a crawl or when passed directly as the seed URL:
- **PDF** (`.pdf`)
- **Word documents** (`.doc`, `.docx`)
- **Plain text** (`.txt`)

Other file types (images, video, CSS, JS, archives, fonts) are skipped automatically.

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
    "total_matches": 13,
    "pages_visited": 2,
    "pages_parsed": 2,
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
    { "url": "https://iana.org/domains/example", "count": 10 },
    { "url": "https://example.com/", "count": 3 }
  ],
  "blocked": [
  ]
}
```

### Limitations
- **JavaScript**: WebGrep processes static content only. It does not execute JavaScript (SPA content may not be fully indexed).
- **Bot Protection**: JavaScript-based challenges (like Cloudflare Managed Challenges) cannot be bypassed, but are detected and reported.
- **Robots.txt**: The tool does not parse `robots.txt`.

### Build
Requires Java 17+ and Maven.
```bash
mvn package
```
Produces `target/WebGrep-1.0-SNAPSHOT.jar`.

---

Written by and belongs to Simon D.
Free to use for personal and educational purposes.
For commercial use please contact me at simon . d . dev symbol proton . me.
