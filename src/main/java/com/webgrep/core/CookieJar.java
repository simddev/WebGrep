package com.webgrep.core;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The crawler's cookie store, applying the rules browsers follow (RFC 6265).
 *
 * <p>Each cookie keeps the attributes its server set, and is sent back only where they allow:
 * <ul>
 *   <li><b>Domain</b> - a cookie set without a {@code Domain} attribute goes back to exactly
 *       the host that set it. One set with {@code Domain=example.com} goes to that domain and
 *       all its subdomains. A server may only name its own domain or a parent of it, never a
 *       sibling, an unrelated site, or a bare top-level domain.</li>
 *   <li><b>Path</b> - sent only to URLs at or below the cookie's path.</li>
 *   <li><b>Secure</b> - sent only over HTTPS.</li>
 *   <li><b>Expires / Max-Age</b> - forgotten once expired; a cookie set with a past date
 *       deletes the stored one.</li>
 * </ul>
 *
 * <p>The crawler asks the jar for a {@code Cookie} header for every request, including each
 * hop of a redirect, so a cookie never travels to a host it was not meant for. The jar is
 * shared with the SPA renderer, which hands cookies to the browser with their attributes
 * intact and adds back the ones the browser sets.
 *
 * <p>Known simplification: without the public suffix list, a domain such as {@code co.uk} is
 * accepted as a cookie domain if a host below it names it. Browsers reject that; for a
 * keyword crawler the only effect is that such a cookie is shared more widely than intended.
 */
public final class CookieJar {

    /**
     * One stored cookie.
     *
     * @param name      the cookie name.
     * @param value     the cookie value.
     * @param domain    the host (host-only cookie) or domain (domain cookie) it belongs to,
     *                  lowercase, without a leading dot.
     * @param hostOnly  {@code true} if it goes back to exactly {@code domain}; {@code false} if
     *                  the subdomains of {@code domain} receive it too.
     * @param path      the path prefix it applies to; always starts with {@code /}.
     * @param secure    whether it is sent over HTTPS only.
     * @param httpOnly  whether page scripts are denied access to it; kept for the browser.
     * @param expiresAt expiry time in epoch milliseconds, or {@code -1} for a session cookie.
     */
    public record Cookie(String name, String value, String domain, boolean hostOnly, String path,
                         boolean secure, boolean httpOnly, long expiresAt) {

        /**
         * Tells whether the cookie has expired.
         *
         * @param now the current time in epoch milliseconds.
         * @return whether the cookie has expired at {@code now}.
         */
        boolean isExpired(long now) {
            return expiresAt >= 0 && expiresAt <= now;
        }

        /**
         * Tells whether the cookie may be sent to a host.
         *
         * @param host a lowercase host name.
         * @return whether the cookie may be sent to {@code host}.
         */
        boolean domainMatches(String host) {
            if (host.equals(domain)) return true;
            return !hostOnly && !isIpAddress(host) && host.endsWith("." + domain);
        }

        /**
         * Path matching of RFC 6265 5.1.4: the cookie path is the request path, or a prefix of it
         * that ends at a {@code /}.
         *
         * @param requestPath the path of the request URL.
         * @return whether the cookie may be sent to a URL with that path.
         */
        boolean pathMatches(String requestPath) {
            if (requestPath.equals(path)) return true;
            if (!requestPath.startsWith(path)) return false;
            return path.endsWith("/") || requestPath.charAt(path.length()) == '/';
        }
    }

    /** Most cookies kept in total; beyond it the oldest is dropped, as browsers do. */
    private static final int MAX_COOKIES = 3000;

    /** Longest lifetime honoured, in seconds (400 days, the cap browsers apply). */
    private static final long MAX_AGE_CAP_SECONDS = 400L * 24 * 3600;

    /** Month name prefixes, for parsing {@code Expires} dates. */
    private static final String[] MONTHS =
            {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /** Stored cookies, oldest first. */
    private final List<Cookie> cookies = new ArrayList<>();

    /** Creates an empty jar. */
    public CookieJar() {
    }

    /**
     * Stores the cookies from a response's {@code Set-Cookie} headers.
     *
     * <p>Headers that are malformed, or that claim a domain the responding host does not belong
     * to, are ignored, as a browser would ignore them.
     *
     * @param url              the URL the response came from.
     * @param setCookieHeaders the raw {@code Set-Cookie} header values; may be empty.
     */
    public synchronized void store(String url, List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return;
        URL parsed = toUrl(url);
        if (parsed == null) return;
        String host = parsed.getHost().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        for (String header : setCookieHeaders) {
            Cookie cookie = parse(header, host, parsed.getPath(), now);
            if (cookie != null) put(cookie);
        }
    }

    /**
     * Adds a cookie whose attributes are already resolved, such as one reported by the browser.
     * It replaces any stored cookie with the same name, domain and path; an expired one only
     * removes that cookie.
     *
     * @param cookie the cookie to store.
     */
    public synchronized void add(Cookie cookie) {
        put(cookie);
    }

    /**
     * Returns the value of the {@code Cookie} request header for a request to {@code url}.
     *
     * @param url the request URL.
     * @return {@code name=value} pairs separated by {@code "; "}, most specific path first; or
     *         {@code null} if no stored cookie applies to the URL.
     */
    public synchronized String header(String url) {
        URL parsed = toUrl(url);
        if (parsed == null) return null;
        String host = parsed.getHost().toLowerCase(Locale.ROOT);
        boolean https = "https".equalsIgnoreCase(parsed.getProtocol());
        String path = parsed.getPath().isEmpty() ? "/" : parsed.getPath();
        long now = System.currentTimeMillis();
        cookies.removeIf(c -> c.isExpired(now));
        List<Cookie> applicable = cookies.stream()
                .filter(c -> c.domainMatches(host) && c.pathMatches(path) && (https || !c.secure()))
                .sorted(Comparator.comparingInt((Cookie c) -> c.path().length()).reversed())
                .toList();
        if (applicable.isEmpty()) return null;
        return applicable.stream().map(c -> c.name() + "=" + c.value()).collect(Collectors.joining("; "));
    }

    /**
     * Returns every stored, unexpired cookie, oldest first.
     *
     * @return a snapshot of the jar's contents.
     */
    public synchronized List<Cookie> all() {
        long now = System.currentTimeMillis();
        cookies.removeIf(c -> c.isExpired(now));
        return List.copyOf(cookies);
    }

    /**
     * Stores a cookie, replacing the one with the same name, domain and path (RFC 6265 5.3).
     * An expired cookie only removes its stored counterpart.
     *
     * @param cookie the cookie to store.
     */
    private void put(Cookie cookie) {
        cookies.removeIf(c -> c.name().equals(cookie.name()) && c.domain().equals(cookie.domain())
                && c.path().equals(cookie.path()));
        if (cookie.isExpired(System.currentTimeMillis())) return;
        cookies.add(cookie);
        if (cookies.size() > MAX_COOKIES) cookies.remove(0);
    }

    /**
     * Parses one {@code Set-Cookie} header value following RFC 6265 section 5.2.
     *
     * <p>Package-private so that tests can check attribute handling directly.
     *
     * @param header      the raw header value, e.g. {@code "id=1; Path=/; Secure"}.
     * @param host        the lowercase host of the URL that sent it.
     * @param requestPath the path of that URL, used for the default cookie path.
     * @param now         the current time in epoch milliseconds.
     * @return the cookie, or {@code null} if the header is malformed or names a domain the
     *         host may not set cookies for.
     */
    static Cookie parse(String header, String host, String requestPath, long now) {
        if (header == null) return null;
        String[] parts = header.split(";");
        int eq = parts[0].indexOf('=');
        if (eq < 0) return null;
        String name = parts[0].substring(0, eq).trim();
        String value = parts[0].substring(eq + 1).trim();
        if (name.isEmpty()) return null;

        String domainAttr = null;
        String pathAttr = null;
        boolean secure = false, httpOnly = false;
        Long maxAge = null, expires = null;
        for (int i = 1; i < parts.length; i++) {
            int e = parts[i].indexOf('=');
            String key = (e < 0 ? parts[i] : parts[i].substring(0, e)).trim().toLowerCase(Locale.ROOT);
            String val = e < 0 ? "" : parts[i].substring(e + 1).trim();
            switch (key) {
                case "domain" -> {
                    String d = val.startsWith(".") ? val.substring(1) : val;
                    if (!d.isEmpty()) domainAttr = d.toLowerCase(Locale.ROOT);
                }
                case "path" -> pathAttr = val.startsWith("/") ? val : null;
                case "secure" -> secure = true;
                case "httponly" -> httpOnly = true;
                case "max-age" -> {
                    try {
                        maxAge = Long.parseLong(val);
                    } catch (NumberFormatException ignored) {
                        // An unparseable Max-Age is ignored, as the RFC requires.
                    }
                }
                case "expires" -> expires = parseDate(val);
                default -> { }
            }
        }

        String domain = host;
        boolean hostOnly = domainAttr == null;
        if (domainAttr != null && !domainAttr.equals(host)) {
            // A server may claim a parent domain of its own host, but not a sibling, an
            // unrelated site, a bare top-level domain, or any domain for an IP address.
            if (isIpAddress(host) || !host.endsWith("." + domainAttr) || domainAttr.indexOf('.') < 0) return null;
            domain = domainAttr;
        }

        long expiresAt = -1;
        if (maxAge != null) {
            expiresAt = maxAge <= 0 ? 0 : now + Math.min(maxAge, MAX_AGE_CAP_SECONDS) * 1000;
        } else if (expires != null) {
            expiresAt = Math.min(expires, now + MAX_AGE_CAP_SECONDS * 1000);
        }
        String path = pathAttr != null ? pathAttr : defaultPath(requestPath);
        return new Cookie(name, value, domain, hostOnly, path, secure, httpOnly, expiresAt);
    }

    /**
     * Computes the default cookie path from a request path (RFC 6265 5.1.4): the request path
     * up to, but not including, its last {@code /}; or {@code /} for a top-level path.
     *
     * @param requestPath the path of the URL that set the cookie.
     * @return the default path.
     */
    static String defaultPath(String requestPath) {
        if (requestPath == null || requestPath.isEmpty() || requestPath.charAt(0) != '/') return "/";
        int last = requestPath.lastIndexOf('/');
        return last == 0 ? "/" : requestPath.substring(0, last);
    }

    /**
     * Parses an {@code Expires} date with the lenient algorithm of RFC 6265 5.1.1, which
     * accepts every date format servers send in practice ({@code Wed, 21 Oct 2026 07:28:00 GMT},
     * {@code Wednesday, 21-Oct-26 07:28:00 GMT}, {@code Wed Oct 21 07:28:00 2026}, ...).
     *
     * @param value the attribute value.
     * @return the time in epoch milliseconds (UTC), or {@code null} if it cannot be parsed - in
     *         which case the attribute is ignored and the cookie lasts for the session.
     */
    static Long parseDate(String value) {
        int hour = -1, minute = -1, second = -1, day = -1, month = -1, year = -1;
        for (String token : value.split("[\\x09\\x20-\\x2F\\x3B-\\x40\\x5B-\\x60\\x7B-\\x7E]+")) {
            if (token.isEmpty()) continue;
            if (hour < 0 && token.matches("\\d{1,2}:\\d{1,2}:\\d{1,2}.*")) {
                String[] hms = token.replaceAll("[^\\d:].*$", "").split(":");
                hour = Integer.parseInt(hms[0]);
                minute = Integer.parseInt(hms[1]);
                second = Integer.parseInt(hms[2]);
            } else if (day < 0 && token.matches("\\d{1,2}(\\D.*)?")) {
                day = Integer.parseInt(token.replaceAll("\\D.*$", ""));
            } else if (month < 0 && token.length() >= 3 && monthIndex(token) >= 0) {
                month = monthIndex(token);
            } else if (year < 0 && token.matches("\\d{2,4}(\\D.*)?")) {
                year = Integer.parseInt(token.replaceAll("\\D.*$", ""));
            }
        }
        if (year >= 70 && year <= 99) year += 1900;
        else if (year >= 0 && year <= 69) year += 2000;
        if (hour < 0 || day < 1 || day > 31 || month < 0 || year < 1601
                || hour > 23 || minute > 59 || second > 59) return null;
        try {
            return LocalDateTime.of(year, month + 1, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.DateTimeException e) {
            return null; // e.g. 31 February
        }
    }

    /**
     * Recognises a month name.
     *
     * @param token a date token of at least three characters.
     * @return the 0-based month if the token starts with a month name, or -1.
     */
    private static int monthIndex(String token) {
        String prefix = token.substring(0, 3).toLowerCase(Locale.ROOT);
        for (int m = 0; m < MONTHS.length; m++) {
            if (MONTHS[m].equals(prefix)) return m;
        }
        return -1;
    }

    /**
     * Tells an IP address literal from a domain name.
     *
     * @param host a host name or address.
     * @return whether {@code host} is an IP address literal.
     */
    static boolean isIpAddress(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * Parses a URL leniently.
     *
     * @param url the URL string.
     * @return the parsed URL, or {@code null} if it is malformed.
     */
    private static URL toUrl(String url) {
        try {
            return new URL(url);
        } catch (Exception e) {
            return null;
        }
    }
}
