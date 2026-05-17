package com.webgrep.core;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webgrep.utils.UrlUtils;

/**
 * Extracts searchable text from HTML pages and binary documents.
 *
 * <p>HTML content is extracted via Jsoup (title, body text, and meta description/keywords tags).
 * All other content types — PDF, DOCX, ODT, XLSX, EPUB, and 100+ other formats — are parsed
 * by Apache Tika. Tika is invoked with a configurable {@code maxStringLength} (derived from
 * {@code --max-bytes}) and wrapped in a 30-second timeout to prevent hangs on corrupt files.
 *
 * <p>If Tika fails or times out, the raw bytes are returned as a UTF-8 string as a last resort.
 *
 * <p>Note: Tika uses the form-feed character ({@code \f}, U+000C) as a page separator in
 * multi-page documents such as PDFs. Callers that need per-page line numbers should split the
 * extracted text on {@code \f} before further processing.
 */
public class ContentExtractor {

    /** Shared Apache Tika instance. Thread-safe; shared across all calls on the same extractor. */
    private final Tika tika;

    /** Maximum number of links to collect from a single page. Prevents runaway allocation
     *  on pages with thousands of generated navigation links. */
    private static final int MAX_LINKS_PER_PAGE = 5000;

    /** Maximum seconds to wait for Apache Tika to parse one document before cancelling. */
    private static final int TIKA_TIMEOUT_SECONDS = 30;

    /**
     * Daemon thread pool used to run Tika parsing with a timeout. Daemon threads do not
     * prevent JVM shutdown if the main thread exits while a parse is in progress.
     */
    private static final ExecutorService TIKA_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    /**
     * Fallback regex for extracting {@code href} values when Jsoup finds no {@code <a>} elements.
     * Applied to the raw response bytes interpreted as UTF-8.
     */
    private static final Pattern LINK_PATTERN =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Constructs a {@code ContentExtractor} with Tika's output capped at the given byte limit.
     *
     * <p>The Tika string-length limit is set independently of the download size limit.
     * The floor is 50 MB: even if the caller passes a small {@code --max-bytes} to throttle
     * network downloads, local documents in {@code --file}/{@code --folder} mode should not
     * be silently truncated mid-way through.
     *
     * @param maxBytes the {@code --max-bytes} option value; used as the basis for Tika's limit.
     */
    public ContentExtractor(long maxBytes) {
        this.tika = new Tika();
        // Cap Tika's string output independently of the download/file-size limit.
        // A small --max-bytes (e.g. 500 KB set to throttle downloads) must not silently
        // truncate text extracted from local documents in --file / --folder mode.
        int tikaLimit = (int) Math.min(Math.max(maxBytes, 50L * 1024 * 1024), Integer.MAX_VALUE);
        this.tika.setMaxStringLength(tikaLimit);
    }

    /**
     * Extracts searchable plain text from a Jsoup-parsed HTML document.
     *
     * <p>Concatenates, in order:
     * <ol>
     *   <li>The page title ({@code <title>} tag).</li>
     *   <li>All visible body text as returned by Jsoup's whitespace-collapsed {@code text()} method.</li>
     *   <li>The content of any {@code <meta name="description">} tag.</li>
     *   <li>The content of any {@code <meta name="keywords">} tag.</li>
     * </ol>
     *
     * @param doc a Jsoup {@link Document} produced by parsing an HTML response.
     * @return a single plain-text string suitable for passing to {@link MatchEngine}.
     */
    public String extractTextFromHtml(Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append(doc.title()).append(" ");
        Element bodyTag = doc.body();
        if (bodyTag != null) {
            sb.append(bodyTag.text());
        } else {
            sb.append(doc.text());
        }
        sb.append(" ").append(doc.select("meta[name=description]").attr("content"));
        sb.append(" ").append(doc.select("meta[name=keywords]").attr("content"));
        return sb.toString();
    }

    /**
     * Extracts searchable plain text from raw binary content using Apache Tika.
     *
     * <p>Tika auto-detects the format from the file name (or URL path) provided in {@code url}
     * and the magic bytes of the content. It supports 100+ formats including PDF, DOCX, ODT,
     * XLSX, EPUB, RTF, and more.
     *
     * <p>If parsing fails or the first attempt returns empty content, a second attempt is made
     * without metadata hints (format detection from magic bytes only). If that also fails,
     * the raw bytes are interpreted as a UTF-8 string as a last-resort fallback.
     *
     * @param body        the raw bytes of the document.
     * @param url         the URL or file name of the document; used by Tika as a format hint
     *                    via the {@code RESOURCE_NAME_KEY} metadata field.
     * @param contentType the HTTP {@code Content-Type} header value if available, or {@code null}.
     * @return the extracted plain text; never {@code null} but may be empty for binary-only files.
     */
    public String extractTextFromBinary(byte[] body, String url, String contentType) {
        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, url);
            if (contentType != null) {
                metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
            }

            String content = tikaParseWithTimeout(body, metadata);
            if (content == null || content.trim().isEmpty()) {
                content = tikaParseWithTimeout(body, null);
            }
            return content != null ? content : new String(body, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Submits a Tika parse job to the shared thread pool and waits up to
     * {@link #TIKA_TIMEOUT_SECONDS} seconds for it to complete.
     *
     * <p>If the timeout expires, the future is cancelled (interrupting the Tika thread) and
     * {@code null} is returned. This prevents one corrupt or overly complex file from
     * blocking the rest of a crawl or folder scan indefinitely.
     *
     * @param body     the raw bytes to parse.
     * @param metadata Tika metadata hints (resource name, content type), or {@code null} to
     *                 rely solely on magic-byte detection.
     * @return the extracted text, or {@code null} if parsing timed out or threw an exception.
     */
    private String tikaParseWithTimeout(byte[] body, Metadata metadata) {
        Future<String> future = TIKA_EXECUTOR.submit(() -> {
            try (InputStream is = new ByteArrayInputStream(body)) {
                return metadata != null ? tika.parseToString(is, metadata) : tika.parseToString(is);
            }
        });
        try {
            return future.get(TIKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts all hyperlinks from an HTML page and returns them as normalised absolute URLs.
     *
     * <p>Primary extraction uses Jsoup's {@code doc.select("a[href]")} which is robust against
     * malformed HTML. Each link is resolved to an absolute URL via {@code element.absUrl("href")},
     * which uses the document's own {@code <base>} tag if present.
     *
     * <p>Fallback extraction applies {@link #LINK_PATTERN} against the raw response bytes when
     * Jsoup found no {@code <a>} elements (e.g. the page was returned as a raw HTML fragment
     * without a proper DOM structure).
     *
     * <p>Both paths filter results through {@link UrlUtils#normalizeUrl} and
     * {@link UrlUtils#isIgnoredLink}, and deduplicate using a {@link LinkedHashSet} that
     * preserves insertion order. Results are capped at {@link #MAX_LINKS_PER_PAGE}.
     *
     * @param doc     the Jsoup-parsed HTML document.
     * @param rawBody the raw response bytes, used for the regex fallback.
     * @param baseUrl the canonical URL of this page, used for resolving relative links.
     * @return a deduplicated list of normalised absolute URLs found on the page.
     */
    public List<String> extractLinks(Document doc, byte[] rawBody, String baseUrl) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        Elements elements = doc.select("a[href]");
        for (Element element : elements) {
            if (links.size() >= MAX_LINKS_PER_PAGE) break;
            String link = element.absUrl("href");
            if (link.isEmpty()) {
                link = element.attr("href");
            }
            String normalizedLink = UrlUtils.normalizeUrl(link, baseUrl);
            if (!normalizedLink.isEmpty() && !UrlUtils.isIgnoredLink(normalizedLink)) {
                links.add(normalizedLink);
            }
        }

        if (elements.isEmpty()) {
            String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
            Matcher linkMatcher = LINK_PATTERN.matcher(bodyStr);
            while (linkMatcher.find() && links.size() < MAX_LINKS_PER_PAGE) {
                String href = org.jsoup.parser.Parser.unescapeEntities(linkMatcher.group(1), true);
                String normalizedLink = UrlUtils.normalizeUrl(href, baseUrl);
                if (!normalizedLink.isEmpty() && !UrlUtils.isIgnoredLink(normalizedLink)) {
                    links.add(normalizedLink); // LinkedHashSet deduplicates in O(1)
                }
            }
        }
        return new ArrayList<>(links);
    }
}
