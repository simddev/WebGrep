package com.webgrep;

import com.webgrep.core.UrlDeduplicator;
import org.junit.Test;
import static org.junit.Assert.*;

public class UrlDeduplicatorTest {

    // ── noise parameters are ignored ───────────────────────────────────────────

    @Test
    public void testSortVariantIsDeduped() {
        // Sort keys and sort directions do not change what a page contains.
        UrlDeduplicator d = new UrlDeduplicator(false);
        String seed = "http://example.com/page.aspx?id=12345";
        assertFalse(d.isDuplicate(seed));
        d.markQueued(seed);

        assertTrue(d.isDuplicate("http://example.com/page.aspx?id=12345&sort=name"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?id=12345&sort=date"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?id=12345&dir=asc"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?id=12345&sort=name&dir=asc"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?orderby=title&order=DESC&id=12345"));
    }

    @Test
    public void testTrackingParametersAreIgnored() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("https://example.com/article?id=7");

        assertTrue(d.isDuplicate("https://example.com/article?id=7&utm_source=newsletter&utm_medium=email"));
        assertTrue(d.isDuplicate("https://example.com/article?fbclid=IwAR0abc&id=7"));
        assertTrue(d.isDuplicate("https://example.com/article?id=7&gclid=xyz"));
        // A tracked link to a page without parameters is that page.
        d.markQueued("https://example.com/about");
        assertTrue(d.isDuplicate("https://example.com/about?utm_campaign=spring"));
    }

    @Test
    public void testSessionTokensAreIgnored() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://forum.example.com/viewtopic.php?t=42");

        assertTrue(d.isDuplicate("http://forum.example.com/viewtopic.php?t=42&sid=0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c"));
        assertTrue(d.isDuplicate("http://forum.example.com/viewtopic.php?t=42&PHPSESSID=abc123"));
        // A short sid is an identifier (a survey, a story), not a session token.
        d.markQueued("http://example.com/survey.php?sid=123");
        assertFalse(d.isDuplicate("http://example.com/survey.php?sid=456"));
    }

    @Test
    public void testDirectionParametersAreNoiseOnlyForDirections() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/files");
        assertTrue(d.isDuplicate("http://example.com/files?dir=desc"));
        // ?dir= can also name a folder, which is different content.
        assertFalse(d.isDuplicate("http://example.com/files?dir=/reports/2024"));
        d.markQueued("http://example.com/shop");
        assertFalse("an order number is content", d.isDuplicate("http://example.com/shop?order=12345"));
    }

    // ── content parameters keep URLs apart ─────────────────────────────────────

    @Test
    public void testContentIdUrlsAreNotDeduped() {
        // file.aspx?fileid=1001 and ?fileid=1002 are different documents
        UrlDeduplicator d = new UrlDeduplicator(false);
        String first = "http://example.com/file.aspx?fileid=1001";
        assertFalse(d.isDuplicate(first));
        d.markQueued(first);

        assertFalse(d.isDuplicate("http://example.com/file.aspx?fileid=1002"));
        assertFalse(d.isDuplicate("http://example.com/file.aspx?fileid=1003"));
        assertFalse(d.isDuplicate("http://example.com/file.aspx?fileid=0"));
    }

    @Test
    public void testPaginationUrlsAreNotDeduped() {
        // page=1, page=2, page=3 are different pages with different content
        UrlDeduplicator d = new UrlDeduplicator(false);
        String page1 = "http://example.com/list.php?page=1";
        assertFalse(d.isDuplicate(page1));
        d.markQueued(page1);

        assertFalse(d.isDuplicate("http://example.com/list.php?page=2"));
        assertFalse(d.isDuplicate("http://example.com/list.php?page=3"));
    }

    @Test
    public void testNextPageOfAQueryIsNotDeduped() {
        // An added parameter is not a harmless variant: page 2 of a search is new content,
        // and so are later pages of a forum thread.
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/search?q=report");
        assertFalse(d.isDuplicate("http://example.com/search?q=report&page=2"));

        d.markQueued("http://forum.example.com/viewtopic.php?t=42");
        assertFalse(d.isDuplicate("http://forum.example.com/viewtopic.php?t=42&start=20"));
    }

    // ── exact URL dedup ────────────────────────────────────────────────────────

    @Test
    public void testExactUrlIsAlwaysDeduped() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        String url = "http://example.com/page?id=1";
        assertFalse(d.isDuplicate(url));
        d.markQueued(url);

        // Exact same URL → always a dup
        assertTrue(d.isDuplicate(url));
    }

    @Test
    public void testExactUrlDedupWorksInAllUrlsMode() {
        UrlDeduplicator d = new UrlDeduplicator(true);
        String url = "http://example.com/page?id=1";
        assertFalse(d.isDuplicate(url));
        d.markQueued(url);
        assertTrue(d.isDuplicate(url));
    }

    // ── no-query-param URLs ────────────────────────────────────────────────────

    @Test
    public void testNoParamUrlDeduped() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        String url = "http://example.com/about";
        assertFalse(d.isDuplicate(url));
        d.markQueued(url);
        assertTrue(d.isDuplicate(url));
    }

    @Test
    public void testBarePathAllowsContentVariantsButNotNoiseVariants() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page");
        // A content parameter selects something else...
        assertFalse(d.isDuplicate("http://example.com/page?cat=docs"));
        assertFalse(d.isDuplicate("http://example.com/page?foo=bar"));
        // ...a sort order does not.
        assertTrue(d.isDuplicate("http://example.com/page?sort=asc"));
        // The exact bare URL itself is still a dup.
        assertTrue(d.isDuplicate("http://example.com/page"));
    }

    @Test
    public void testDifferentBasePathsAreIndependent() {
        // Dedup state for one path must not affect another
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/file.aspx?fileid=1");

        // Different base path is unrelated
        assertFalse(d.isDuplicate("http://example.com/list.aspx?fileid=1"));
        assertFalse(d.isDuplicate("http://example.com/other.aspx?fileid=9999"));
    }

    // ── param ordering ─────────────────────────────────────────────────────────

    @Test
    public void testParamOrderDoesNotMatterForDuplication() {
        // ?a=1&b=2 and ?b=2&a=1 are functionally the same page
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page?a=1&b=2");

        assertTrue(d.isDuplicate("http://example.com/page?b=2&a=1"));
        assertTrue(d.isDuplicate("http://example.com/page?b=2&utm_source=x&a=1"));
        // An extra content parameter is a different page.
        assertFalse(d.isDuplicate("http://example.com/page?b=2&a=1&c=3"));
    }

    // ── --all-urls mode ────────────────────────────────────────────────────────

    @Test
    public void testAllUrlsModeDisablesSortDedup() {
        // With --all-urls, sort variants should NOT be deduped
        UrlDeduplicator d = new UrlDeduplicator(true);
        d.markQueued("http://example.com/page?id=12345");

        assertFalse(d.isDuplicate("http://example.com/page?id=12345&sort=name"));
        assertFalse(d.isDuplicate("http://example.com/page?id=12345&utm_source=x"));
    }

    @Test
    public void testAllUrlsModeAllowsAllContentIds() {
        UrlDeduplicator d = new UrlDeduplicator(true);
        d.markQueued("http://example.com/file.aspx?fileid=1001");

        assertFalse(d.isDuplicate("http://example.com/file.aspx?fileid=1002"));
        assertFalse(d.isDuplicate("http://example.com/file.aspx?fileid=1003"));
    }

    // ── size tracking ──────────────────────────────────────────────────────────

    @Test
    public void testSizeTracksQueuedUrls() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        assertEquals(0, d.size());

        d.markQueued("http://example.com/a");
        assertEquals(1, d.size());

        d.markQueued("http://example.com/b");
        assertEquals(2, d.size());

        // Marking the same URL again is a no-op, so the size stays 2.
        d.markQueued("http://example.com/a");
        assertEquals(2, d.size());
    }

    // ── edge cases ─────────────────────────────────────────────────────────────

    @Test
    public void testEmptyQueryString() {
        // URL ending in ? with no actual params is the same page as without the ?
        UrlDeduplicator d = new UrlDeduplicator(false);
        String url = "http://example.com/page?";
        assertFalse(d.isDuplicate(url));
        d.markQueued(url);
        assertTrue(d.isDuplicate(url));
        assertTrue(d.isDuplicate("http://example.com/page"));
    }

    @Test
    public void testSingleParamNoValue() {
        // ?flag (no =) is a parameter like any other
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page?flag");
        assertTrue(d.isDuplicate("http://example.com/page?flag&utm_source=x"));
        assertFalse(d.isDuplicate("http://example.com/page?flag&extra=1"));
    }

    @Test
    public void testMultipleDistinctDocumentsAllQueued() {
        // Simulate a site with 5 documents all at the same base path
        UrlDeduplicator d = new UrlDeduplicator(false);
        String[] docs = {
            "http://site.com/doc.aspx?id=1",
            "http://site.com/doc.aspx?id=2",
            "http://site.com/doc.aspx?id=3",
            "http://site.com/doc.aspx?id=4",
            "http://site.com/doc.aspx?id=5",
        };

        for (String doc : docs) {
            assertFalse("Should not be a dup: " + doc, d.isDuplicate(doc));
            d.markQueued(doc);
        }
        assertEquals(5, d.size());

        // Each is now a dup of itself
        for (String doc : docs) {
            assertTrue("Should be a dup now: " + doc, d.isDuplicate(doc));
        }
    }

    @Test
    public void testMixedSortAndContentParams() {
        // Realistic mix: some are sort variants, some are genuinely different content
        UrlDeduplicator d = new UrlDeduplicator(false);

        String seed = "http://court.cz/subject.aspx?id=100";
        assertFalse(d.isDuplicate(seed));
        d.markQueued(seed);

        // Sort variants → dup
        assertTrue(d.isDuplicate("http://court.cz/subject.aspx?id=100&sort=date"));
        assertTrue(d.isDuplicate("http://court.cz/subject.aspx?id=100&sort=name&dir=asc"));

        // Different subject → not a dup
        assertFalse(d.isDuplicate("http://court.cz/subject.aspx?id=200"));
        d.markQueued("http://court.cz/subject.aspx?id=200");

        // The sort variant of the second subject is recognised too: every queued URL counts,
        // not only the first one seen for the path.
        assertTrue(d.isDuplicate("http://court.cz/subject.aspx?id=200&sort=date"));

        // Document files with different IDs → not dups
        String doc1 = "http://court.cz/file.aspx?fileid=9477999";
        assertFalse(d.isDuplicate(doc1));
        d.markQueued(doc1);

        assertFalse(d.isDuplicate("http://court.cz/file.aspx?fileid=1234"));
        assertFalse(d.isDuplicate("http://court.cz/file.aspx?fileid=5678"));
    }

    // ── http/https scheme deduplication ───────────────────────────────────────

    @Test
    public void testHttpAndHttpsVariantsAreDeduped() {
        // Visiting http://example.com/page should block https://example.com/page (same page)
        UrlDeduplicator d = new UrlDeduplicator(false);
        assertFalse(d.isDuplicate("http://example.com/page"));
        d.markQueued("http://example.com/page");

        assertTrue(d.isDuplicate("https://example.com/page"));
        assertTrue(d.isDuplicate("http://example.com/page"));
    }

    @Test
    public void testHttpsQueuedBlocksHttpVariant() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        assertFalse(d.isDuplicate("https://example.com/page"));
        d.markQueued("https://example.com/page");

        assertTrue(d.isDuplicate("http://example.com/page"));
    }

    @Test
    public void testSchemeDeduplicationWithQueryParams() {
        // Noise variants are recognised across http/https too
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("https://example.com/list?sort=asc");

        assertTrue(d.isDuplicate("http://example.com/list?sort=desc"));
        assertFalse("page 2 is new content", d.isDuplicate("http://example.com/list?sort=asc&page=2"));
    }

    // ── bare path after parameterised variant ─────────────────────────────────

    @Test
    public void testBarePathNotBlockedByParameterisedVariant() {
        // Queuing /article?id=5 must NOT suppress a later visit to /article (bare path).
        // Both may carry distinct content and must be visited independently.
        UrlDeduplicator d = new UrlDeduplicator(false);
        assertFalse(d.isDuplicate("http://example.com/article?id=5"));
        d.markQueued("http://example.com/article?id=5");

        assertFalse("bare path must not be blocked by a queued parameterised variant",
                d.isDuplicate("http://example.com/article"));
    }

    @Test
    public void testBarePathAfterBarePathIsDeduped() {
        // Visiting the bare path and then seeing the same bare path again IS a duplicate.
        UrlDeduplicator d = new UrlDeduplicator(false);
        assertFalse(d.isDuplicate("http://example.com/about"));
        d.markQueued("http://example.com/about");

        assertTrue("same bare path twice must be a duplicate",
                d.isDuplicate("http://example.com/about"));
    }

    // ── same page under another spelling ──────────────────────────────────────

    @Test
    public void testSameKeyRecognisesVariantsOfOneUrl() {
        UrlDeduplicator d = new UrlDeduplicator(false);
        assertTrue(d.sameKey("http://example.com/a", "https://example.com/a"));
        assertTrue(d.sameKey("https://example.com/a?id=1", "http://example.com/a?utm_source=x&id=1"));
        assertFalse(d.sameKey("https://example.com/a", "https://example.com/b"));
        assertFalse(d.sameKey("https://example.com/a?id=1", "https://example.com/a?id=2"));

        UrlDeduplicator exact = new UrlDeduplicator(true);
        assertTrue(exact.sameKey("http://example.com/a", "https://example.com/a"));
        assertFalse(exact.sameKey("https://example.com/a", "https://example.com/a?utm_source=x"));
    }
}
