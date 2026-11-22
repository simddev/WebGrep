package com.webgrep.core;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link CookieJar}: each cookie goes back only where its attributes allow.
 */
public class CookieJarTest {

    private static CookieJar jarWith(String url, String... setCookies) {
        CookieJar jar = new CookieJar();
        jar.store(url, List.of(setCookies));
        return jar;
    }

    @Test
    public void testHostOnlyCookieIsNotSentToSiblingOrParent() {
        CookieJar jar = jarWith("https://www.example.com/login", "SID=abc; Path=/");
        assertEquals("SID=abc", jar.header("https://www.example.com/page"));
        assertNull("Sibling subdomain must not receive a host-only cookie",
                jar.header("https://docs.example.com/page"));
        assertNull("Parent domain must not receive a host-only cookie",
                jar.header("https://example.com/page"));
    }

    @Test
    public void testDomainCookieIsSharedWithSubdomains() {
        CookieJar jar = jarWith("https://www.example.com/login", "SID=abc; Domain=example.com; Path=/");
        assertEquals("SID=abc", jar.header("https://docs.example.com/page"));
        assertEquals("SID=abc", jar.header("https://example.com/page"));
        // The leading dot of the old syntax means the same thing.
        CookieJar dotted = jarWith("https://www.example.com/", "SID=abc; Domain=.example.com");
        assertEquals("SID=abc", dotted.header("https://deep.docs.example.com/"));
    }

    @Test
    public void testCookieNeverReachesUnrelatedOrLookalikeHost() {
        CookieJar jar = jarWith("https://www.example.com/", "SID=abc; Domain=example.com");
        assertNull(jar.header("https://other-site.org/collect"));
        assertNull("A plain suffix match is not a domain match",
                jar.header("https://notexample.com/collect"));
    }

    @Test
    public void testServerCannotSetCookiesForOtherDomains() {
        // Sibling, unrelated domain, and bare top-level domain are all refused.
        assertNull(jarWith("https://www.example.com/", "a=1; Domain=docs.example.com").header("https://docs.example.com/"));
        assertNull(jarWith("https://www.example.com/", "a=1; Domain=evil.org").header("https://evil.org/"));
        assertTrue(jarWith("https://www.example.com/", "a=1; Domain=com").all().isEmpty());
        // An IP address can only set cookies for itself.
        assertTrue(jarWith("http://127.0.0.1/", "a=1; Domain=0.0.1").all().isEmpty());
    }

    @Test
    public void testSecureCookieIsSentOverHttpsOnly() {
        CookieJar jar = jarWith("https://example.com/", "token=s3cret; Secure", "plain=1");
        assertEquals("token=s3cret; plain=1", jar.header("https://example.com/"));
        assertEquals("plain=1", jar.header("http://example.com/"));
    }

    @Test
    public void testPathAttributeAndDefaultPath() {
        CookieJar jar = jarWith("https://example.com/shop/cart/view", "basket=7; Path=/shop", "last=x");
        assertEquals("basket=7", jar.header("https://example.com/shop"));
        assertNull("/shopping is not below /shop", jar.header("https://example.com/shopping"));
        // Without a Path attribute the default is the request path's directory: /shop/cart.
        assertEquals("last=x; basket=7", jar.header("https://example.com/shop/cart/other"));
        assertEquals("/shop/cart", CookieJar.defaultPath("/shop/cart/view"));
        assertEquals("/", CookieJar.defaultPath("/index.html"));
        assertEquals("/", CookieJar.defaultPath(""));
    }

    @Test
    public void testLaterCookieReplacesEarlierAndPastExpiryDeletes() {
        CookieJar jar = jarWith("https://example.com/", "id=1");
        jar.store("https://example.com/", List.of("id=2"));
        assertEquals("id=2", jar.header("https://example.com/"));

        jar.store("https://example.com/", List.of("id=gone; Max-Age=0"));
        assertNull(jar.header("https://example.com/"));

        jar.store("https://example.com/", List.of("x=1", "x=gone; Expires=Thu, 01 Jan 1970 00:00:00 GMT"));
        assertNull(jar.header("https://example.com/"));
    }

    @Test
    public void testMaxAgeTakesPrecedenceOverExpires() {
        long now = System.currentTimeMillis();
        CookieJar.Cookie c = CookieJar.parse("a=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=60",
                "example.com", "/", now);
        assertNotNull(c);
        assertEquals(now + 60_000, c.expiresAt());
    }

    @Test
    public void testMalformedHeadersAreIgnored() {
        long now = System.currentTimeMillis();
        assertNull(CookieJar.parse("no-equals-sign", "example.com", "/", now));
        assertNull(CookieJar.parse("=value-without-name", "example.com", "/", now));
        // An unparseable Expires is ignored, leaving a session cookie - not a deletion.
        CookieJar.Cookie c = CookieJar.parse("a=1; Expires=someday", "example.com", "/", now);
        assertNotNull(c);
        assertEquals(-1, c.expiresAt());
    }

    @Test
    public void testExpiresDateFormatsServersUse() {
        long expected = LocalDateTime.of(2026, 10, 21, 7, 28, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        assertEquals(Long.valueOf(expected), CookieJar.parseDate("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(Long.valueOf(expected), CookieJar.parseDate("Wednesday, 21-Oct-26 07:28:00 GMT"));
        assertEquals(Long.valueOf(expected), CookieJar.parseDate("Wed Oct 21 07:28:00 2026"));
        assertNull(CookieJar.parseDate("Wed, 31 Feb 2026 07:28:00 GMT"));
        assertNull(CookieJar.parseDate("tomorrow"));
    }

    @Test
    public void testMostSpecificPathComesFirst() {
        CookieJar jar = jarWith("https://example.com/", "a=root; Path=/", "b=docs; Path=/docs");
        assertEquals("b=docs; a=root", jar.header("https://example.com/docs/page"));
    }

    @Test
    public void testBrowserCookieRoundTripKeepsHostOnlyFlag() {
        CookieJar.Cookie hostOnly = new CookieJar.Cookie("a", "1", "www.example.com", true, "/", true, true, -1);
        CookieJar.Cookie shared = new CookieJar.Cookie("b", "2", "example.com", false, "/docs", false, false, 2_000_000_000_000L);
        for (CookieJar.Cookie c : List.of(hostOnly, shared)) {
            assertEquals(c, PlaywrightRenderer.fromBrowserCookie(PlaywrightRenderer.toBrowserCookie(c)));
        }
        assertEquals("Domain cookies carry a leading dot in the browser",
                ".example.com", PlaywrightRenderer.toBrowserCookie(shared).domain);
    }
}
