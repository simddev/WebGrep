# Changelog

Notable changes in each WebGrep release. Versions that were never tagged are included in the next tagged release. Each release also has notes on the [releases page](https://github.com/simddev/WebGrep/releases).

## [1.3.0] - 2026-11-22

### Security

- **Cookies no longer follow redirects to other hosts.** Redirects were followed inside Jsoup, which sends the cookies a request starts with to every hop of the chain, so a session cookie set by one site could reach another site through a single redirect. WebGrep now follows redirects one hop at a time and builds each hop's `Cookie` header for that hop's own URL.
- **Cookies follow browser rules (RFC 6265).** The cookie store kept only names and values per host and, with a `www.` seed, shared them across every subdomain. Cookies now keep their `Domain`, `Path`, `Secure` and expiry attributes: a cookie set without `Domain` goes back only to the host that set it, a `Secure` cookie is never sent over plain HTTP, and a server can no longer set cookies for an unrelated domain. Cookies handed to and from the SPA browser keep their attributes too.
- **Terminal escape sequences are neutralised.** A page could put control characters into a link target, such as a `<meta http-equiv="refresh">` URL, and they reached the progress line and the results intact, where they could retitle the terminal or clear the screen. URLs are now cleaned the way browsers clean them, and every string from outside WebGrep that is printed to the terminal (URLs, snippets, file names, server error messages) has its control characters escaped. JSON output now also escapes DEL and C1 control characters.

### Changed

- **Redirects to other sites are not followed without `--allow-external`.** An in-site link that redirected elsewhere used to be fetched, searched and reported. Such redirects are now listed under "Redirected to another site" instead, and in JSON as `stats.errors.skipped_offsite` and a `redirected_offsite` array. A seed URL that redirects to a new domain still moves the crawl there.
- **Duplicate URL detection ignores only noise parameters.** A URL used to count as a duplicate whenever its parameters were a superset of an earlier URL's, which silently skipped the next page of search results and later pages of forum threads. Now only parameters that never change a page's content are ignored: tracking (`utm_*`, `fbclid`, `gclid` and similar), session IDs, sort keys, and `order`, `dir` or `direction` when the value is a sort direction such as `asc`.
- **`--max-pages` counts visited pages only.** Links that turned out to be broken used up the budget, so good links found on the same page were never queued. With a small budget, `--dfs` now also really explores depth first.
- **Fuzzy mode respects word boundaries.** `sofa` no longer matches "so far away". Punctuation inside words is still ignored (`node.js` finds `nodejs`), and typo matches now come with snippets.
- **Browsers are installed into WebGrep's own folder**, `~/.cache/webgrep/browsers` on Linux, instead of Playwright's shared cache, which any other program's Playwright installation can clean out. Browsers already in the shared cache are still used. `PLAYWRIGHT_BROWSERS_PATH` overrides the folder.
- **An installed Firefox is no longer tried.** Playwright can only drive its own patched Firefox build, so a system Firefox never started, and `--install-browser` could still report it as ready. `--install-browser` now only reports a browser as ready after starting it.
- **PDF line numbers count printed lines.** Blank lines in extracted PDF text are layout artifacts, so they are no longer counted, and the line number matches the line on the page.

### Fixed

- PDF page numbers were never reported, because Tika separates PDF pages with blank lines rather than form feeds. Page boundaries are now marked during extraction, so `--file` and `--folder` report `p.N, l.N` for PDFs, including one-page PDFs.
- Phrases broken across lines were missed by `--file` and `--folder`, which searched one line at a time. Pages are now searched as a whole, and each match is reported on the line where it starts.
- Phrases containing punctuation, such as `node.js tutorial`, did not match across a line break in any mode. A space in the keyword now matches any run of whitespace in the text.
- A folder containing a named pipe hung the scan. Folder scans now read regular files only.
- A `--folder` path that was a symbolic link scanned nothing.
- A site whose front page is a zero-delay meta refresh lost a level of depth and found nothing at the default `--depth 1`.
- Text from rendered SPA pages ran adjacent elements together ("HelloWorld"), so phrases did not match and snippets were hard to read.
- Snippets could leave out the match: on long lines in local files, and in text without spaces (Chinese, Japanese, minified code) in web results.
- `HTTPS://` and other uppercase URL schemes were rejected.
- Keywords starting with `-` could not be searched. They are now written attached: `--keyword=--force`.
- `--file` given a folder or a special file now gives a clear error.

### Build

- The build compiles with `--release 17`, so it fails if the code uses an API newer than Java 17.
- The fat JAR no longer contains two copies of the Commons Logging classes, and the dependencies' licence and notice files are merged instead of overwritten.
- The Docker image runs as an unprivileged user, and `.dockerignore` limits the build context to `pom.xml` and `src/`.
- CI builds and tests every push on Java 17 and 21.

### Tests

209 to 259. New tests crawl a local HTTP server (redirects, cookies, retries after HTTP 429, size limits, the page budget, pagination) and parse real PDFs generated during the test run.

## [1.2.0] - 2026-08-21

### Security

- With a `www.` seed and `--allow-external`, a hop to an unrelated site was sent the seed domain's session cookies.

### Fixed

- Multi-word keywords that span punctuation (`real time` against `real-time`) were counted but showed no snippet.
- Zero-delay `<meta http-equiv="refresh">` redirects were not followed.
- SPA pages ignored the `<title>` and meta tags.
- Root-relative document URLs found in SPA JSON responses lost a non-default port.
- A newline-only exception message crashed the crawl, and wrapped exception class names showed up in error output.
- Text and JSON output ordered results with equal counts differently.
- `localhost:8080` was rejected as a seed URL.

### Performance

- Matching is 3 to 8 times faster on large pages: the diacritic-stripping pass is a single character pass with an ASCII fast path, and it is skipped when it cannot find anything new.

### Tests

192 to 209.

## [1.1.8] - 2026-07-30

### Fixed

- Default mode no longer matches multi-word keywords against words run together, and finds phrases broken across lines in PDF text.
- Control characters are stripped from snippets, and snippets are capped at 500 characters.
- Stricter option parsing: single-dash long flags, stray positional arguments, URLs without a host, and `--timeout-ms 0` are rejected.
- A bare path is no longer treated as a duplicate of a parameterised variant, and two links that redirect to the same page are counted once.
- `--max-bytes` edge cases: a body truncated at exactly the limit is detected, and values near `Long.MAX_VALUE` no longer overflow.
- Cookies set on an HTTP 429 response are kept for the retry, and negative `Retry-After` values are ignored.
- Folder scans continue past unreadable subdirectories.
- `--install-browser` runs the installer in a separate process, so it can report success.
- JSON output keeps emoji and other supplementary characters intact.
- Lowercasing no longer depends on the system locale.

### Tests

187 to 192.

## [1.1.7] - 2026-06-06

### Fixed

- Text containing both a plain and an accented variant ("cafe" and "Café") now produces a snippet for each.
- The thread's interrupt flag is restored when a document parse is interrupted.
- The README now says when the diacritic-stripping pass is skipped.

### Tests

169 to 174.

## [1.1.5] - 2026-05-29

### Fixed

- Keywords with ASCII special characters (`more*`, `node.js`, `.NET`) no longer produce false positives. The diacritic-stripping pass, which removed those characters, is skipped for such keywords.

## [1.1.4] - 2026-05-17

### Added

- `--file` and `--folder` modes for searching local documents, reporting the line of each match.
- Rendering of JavaScript single-page applications (Angular, React, Vue) in a headless browser, including document links found in the page's JSON API responses.
- Context snippets for every match in web crawl results.
- A Docker image.

### Fixed

- Relative links in SPAs that use `<base href>` are resolved correctly.
- Document links are followed regardless of the crawl depth.
- Snippets show diacritic variants ("Tomas" finds "Tomáš").

## [1.0.0] - 2026-03-18

First release.

- Multi-level web crawl, breadth-first or depth-first, with domain scoping, a politeness delay, size limits and retries on HTTP 429.
- Text extraction from HTML with Jsoup, and from PDF, DOCX and other documents with Apache Tika.
- Default (case- and diacritic-insensitive), exact and fuzzy (Levenshtein) matching.
- Smart URL deduplication, `--max-hits`, and text or JSON output.

[1.3.0]: https://github.com/simddev/WebGrep/releases/tag/v1.3.0
[1.2.0]: https://github.com/simddev/WebGrep/releases/tag/v1.2.0
[1.1.8]: https://github.com/simddev/WebGrep/releases/tag/v1.1.8
[1.1.7]: https://github.com/simddev/WebGrep/releases/tag/v1.1.7
[1.1.5]: https://github.com/simddev/WebGrep/releases/tag/v1.1.5
[1.1.4]: https://github.com/simddev/WebGrep/releases/tag/v1.1.4
[1.0.0]: https://github.com/simddev/WebGrep/tree/v1.0.0
