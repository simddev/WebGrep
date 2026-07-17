package com.webgrep.utils;

import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility methods for URL normalisation and link filtering.
 *
 * <p>{@link #normalizeUrl(String, String)} resolves relative URLs against a base, lower-cases
 * the scheme and host, strips default ports, and collapses duplicate slashes in the path.
 * Protocol-relative URLs ({@code //example.com/...}) are expanded using the base URL's scheme.
 *
 * <p>{@link #isIgnoredLink(String)} returns {@code true} for URLs that are never useful to
 * crawl - static assets (CSS, JS, images, fonts, video, archives), known social-share redirect
 * URLs, and tag/author taxonomy pages. Document URLs (PDF, DOCX, TXT) are explicitly
 * <em>not</em> ignored so that Tika can extract their text.
 */
public class UrlUtils {

    /** Pre-compiled pattern for collapsing two or more consecutive slashes into one. */
    private static final Pattern DOUBLE_SLASH = Pattern.compile("/{2,}");

    /**
     * Resolves {@code urlString} to a canonical absolute {@code http://} or {@code https://} URL.
     *
     * <p>Resolution steps (in order):
     * <ol>
     *   <li>Protocol-relative URLs ({@code //host/path}) are prefixed with {@code https:} or
     *       {@code http:} based on the scheme of {@code baseUrlString}.</li>
     *   <li>Non-{@code http(s)} schemes ({@code javascript:}, {@code ftp:}, {@code data:}, etc.)
     *       are rejected and return {@code ""}.</li>
     *   <li>Relative URLs are resolved against {@code baseUrlString} using {@link URL#URL(URL, String)}.</li>
     *   <li>The resulting URL is normalised: scheme and host are lower-cased, default ports are
     *       stripped, and runs of {@code //} in the path are collapsed to {@code /}.</li>
     *   <li>Fragments ({@code #section}) are automatically dropped because {@link URL#getPath()}
     *       and {@link URL#getQuery()} do not include them.</li>
     * </ol>
     *
     * @param urlString     the raw URL to normalise; may be absolute, relative, or protocol-relative.
     * @param baseUrlString the page URL to resolve relative links against; may be {@code null}.
     * @return the canonical absolute URL, or {@code ""} if the URL is invalid or uses a
     *         non-{@code http(s)} scheme.
     */
    public static String normalizeUrl(String urlString, String baseUrlString) {
        if (urlString == null || urlString.isEmpty()) {
            return "";
        }
        if (urlString.startsWith("//")) {
            if (baseUrlString != null && baseUrlString.startsWith("https")) {
                urlString = "https:" + urlString;
            } else {
                urlString = "http:" + urlString;
            }
        }
        // Reject non-http(s) schemes immediately so that javascript:, data:, ftp:, etc.
        // never reach the fetcher even if they somehow survive as absolute-looking strings.
        if (urlString.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            int colon = urlString.indexOf(':');
            String scheme = urlString.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return "";
        }
        if (!urlString.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            if (baseUrlString != null && !baseUrlString.isEmpty()) {
                try {
                    URL base = new URL(baseUrlString);
                    URL abs = new URL(base, urlString);
                    urlString = abs.toString();
                } catch (Exception e) {
                    if (!urlString.startsWith("http")) {
                        urlString = "http://" + urlString;
                    }
                }
            } else if (!urlString.startsWith("http")) {
                urlString = "http://" + urlString;
            }
        }
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!protocol.equals("http") && !protocol.equals("https")) return "";
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return "";

            int port = url.getPort();
            String path = url.getPath();
            String query = url.getQuery();

            if (path.isEmpty()) {
                path = "/";
            }
            path = DOUBLE_SLASH.matcher(path).replaceAll("/");

            StringBuilder sb = new StringBuilder();
            sb.append(protocol).append("://").append(host);
            if (port != -1 && port != url.getDefaultPort()) {
                sb.append(":").append(port);
            }
            sb.append(path);
            if (query != null) {
                sb.append("?").append(query);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns {@code true} if {@code url} points to a known document format that should be
     * downloaded and parsed by Apache Tika.
     *
     * <p>The check is performed on the URL path after stripping any fragment ({@code #…}) and
     * query string ({@code ?…}), so {@code /files/report.pdf?v=2} is correctly identified as a PDF.
     *
     * <p>Recognised extensions: {@code .pdf}, {@code .doc}, {@code .docx}, {@code .txt},
     * {@code .xlsx}, {@code .odt}, {@code .pptx}, {@code .csv}.
     *
     * <p>Used by {@link com.webgrep.core.Crawler} to separate links into document links (which
     * bypass the depth limit) and navigation links (which respect it).
     *
     * @param url the normalised absolute URL to test.
     * @return {@code true} if the URL path ends in a recognised document extension.
     */
    public static boolean isDocumentLink(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int hashIdx = lower.indexOf('#');
        if (hashIdx != -1) lower = lower.substring(0, hashIdx);
        int queryIdx = lower.indexOf('?');
        if (queryIdx != -1) lower = lower.substring(0, queryIdx);
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
            || lower.endsWith(".txt") || lower.endsWith(".xlsx") || lower.endsWith(".odt")
            || lower.endsWith(".pptx") || lower.endsWith(".csv");
    }

    /**
     * Returns {@code true} if {@code url} should never be fetched or enqueued.
     *
     * <p>The following categories are ignored:
     * <ul>
     *   <li><b>Static assets</b> - CSS, JavaScript, images ({@code .png}, {@code .jpg},
     *       {@code .jpeg}, {@code .gif}, {@code .svg}, {@code .ico}), web fonts ({@code .woff},
     *       {@code .woff2}, {@code .ttf}, {@code .otf}), audio/video ({@code .mp3}, {@code .mp4},
     *       {@code .wav}, {@code .avi}, {@code .mov}, {@code .wmv}), and archives ({@code .zip},
     *       {@code .rar}, {@code .7z}, {@code .tar.gz}).</li>
     *   <li><b>Social share redirects</b> - Google Ads, DoubleClick, Facebook sharer, Twitter
     *       intent, LinkedIn share, Pinterest pin.</li>
     *   <li><b>Taxonomy pages</b> - paths containing {@code /tag/}, {@code /tags/}, or
     *       {@code /author/}, which typically generate thousands of near-duplicate listing pages.</li>
     * </ul>
     *
     * <p>Document URLs (PDF, DOCX, etc.) are explicitly <em>not</em> ignored - {@link #isDocumentLink}
     * is checked first and takes priority, ensuring those files are always fetched.
     *
     * @param url the normalised absolute URL to test.
     * @return {@code true} if the URL should be skipped without fetching.
     */
    public static boolean isIgnoredLink(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int hashIdx = lower.indexOf('#');
        if (hashIdx != -1) {
            lower = lower.substring(0, hashIdx);
        }

        int queryIdx = lower.indexOf('?');
        if (queryIdx != -1) {
            lower = lower.substring(0, queryIdx);
        }

        if (isDocumentLink(url)) {
            return false;
        }

        return lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".svg") || lower.endsWith(".ico") || lower.endsWith(".woff")
                || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".otf")
                || lower.endsWith(".mp3") || lower.endsWith(".mp4") || lower.endsWith(".wav")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".wmv")
                || lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar.gz")
                || lower.contains("googleads") || lower.contains("doubleclick")
                || lower.contains("facebook.com/sharer") || lower.contains("twitter.com/intent/tweet")
                || lower.contains("linkedin.com/share") || lower.contains("pinterest.com/pin")
                || lower.contains("/tag/") || lower.contains("/tags/") || lower.contains("/author/");
    }
}
