package com.webgrep;

import com.webgrep.core.UrlDeduplicator;
import org.junit.Test;
import static org.junit.Assert.*;

public class UrlDeduplicatorTest {

    // ── core sort/filter dedup ─────────────────────────────────────────────────

    @Test
    public void testSortVariantIsDeduped() {
        // justice.cz pattern: seed has ?subjkod=X, sort variants add ?subjkod=X&r=agenda etc.
        UrlDeduplicator d = new UrlDeduplicator(false);
        String seed = "http://example.com/page.aspx?subjkod=207020";
        assertFalse(d.isDuplicate(seed));
        d.markQueued(seed);

        // Same base + same primary param + extra sort param → duplicate
        assertTrue(d.isDuplicate("http://example.com/page.aspx?subjkod=207020&r=agenda"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?subjkod=207020&r=od"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?subjkod=207020&s=l"));
        assertTrue(d.isDuplicate("http://example.com/page.aspx?subjkod=207020&r=agenda&s=l"));
    }

    @Test
    public void testContentIdUrlsAreNotDeduped() {
        // soubor.aspx?souborid=9477999 and ?souborid=1234 are different documents
        UrlDeduplicator d = new UrlDeduplicator(false);
        String first = "http://example.com/soubor.aspx?souborid=9477999";
        assertFalse(d.isDuplicate(first));
        d.markQueued(first);

        // Different value for the same key → NOT a duplicate
        assertFalse(d.isDuplicate("http://example.com/soubor.aspx?souborid=1234"));
        assertFalse(d.isDuplicate("http://example.com/soubor.aspx?souborid=5678"));
        assertFalse(d.isDuplicate("http://example.com/soubor.aspx?souborid=0"));
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
    public void testNoParamBaseBlocksAllVariants() {
        // Once base path without params is visited, any parameterized variant is a dup
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page");
        assertTrue(d.isDuplicate("http://example.com/page?sort=asc"));
        assertTrue(d.isDuplicate("http://example.com/page?sort=desc"));
        assertTrue(d.isDuplicate("http://example.com/page?foo=bar"));
    }

    @Test
    public void testDifferentBasePathsAreIndependent() {
        // Dedup state for one path must not affect another
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/soubor.aspx?souborid=1");

        // Different base path is unrelated
        assertFalse(d.isDuplicate("http://example.com/vyveseni.aspx?souborid=1"));
        assertFalse(d.isDuplicate("http://example.com/other.aspx?souborid=9999"));
    }

    // ── param ordering ─────────────────────────────────────────────────────────

    @Test
    public void testParamOrderDoesNotMatterForDuplication() {
        // ?a=1&b=2 and ?b=2&a=1 are functionally the same page
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page?a=1&b=2");

        // Reverse order + extra param → still a dup (canonical {a=1,b=2} ⊆ {b=2,a=1,c=3})
        assertTrue(d.isDuplicate("http://example.com/page?b=2&a=1&c=3"));
    }

    @Test
    public void testParamOrderDoesNotPreventFirstVisit() {
        // ?b=2&a=1 is the same canonical as ?a=1&b=2, subsequent visit with extra param is a dup
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page?b=2&a=1");

        assertTrue(d.isDuplicate("http://example.com/page?a=1&b=2&extra=x"));
    }

    // ── --all-urls mode ────────────────────────────────────────────────────────

    @Test
    public void testAllUrlsModeDisablesSortDedup() {
        // With --all-urls, sort variants should NOT be deduped
        UrlDeduplicator d = new UrlDeduplicator(true);
        d.markQueued("http://example.com/page?subjkod=207020");

        assertFalse(d.isDuplicate("http://example.com/page?subjkod=207020&r=agenda"));
        assertFalse(d.isDuplicate("http://example.com/page?subjkod=207020&r=od"));
    }

    @Test
    public void testAllUrlsModeAllowsAllContentIds() {
        UrlDeduplicator d = new UrlDeduplicator(true);
        d.markQueued("http://example.com/soubor.aspx?souborid=9477999");

        assertFalse(d.isDuplicate("http://example.com/soubor.aspx?souborid=1234"));
        assertFalse(d.isDuplicate("http://example.com/soubor.aspx?souborid=5678"));
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

        // Marking a dup (same URL) shouldn't increase size — but isDuplicate should have returned true;
        // markQueued of the same URL does add it to queued (no-op since it's a Set), so size stays 2
        d.markQueued("http://example.com/a");
        assertEquals(2, d.size());
    }

    // ── edge cases ─────────────────────────────────────────────────────────────

    @Test
    public void testEmptyQueryString() {
        // URL ending in ? with no actual params — treated as having canonical {""};
        // the exact URL is still tracked and deduped on re-encounter
        UrlDeduplicator d = new UrlDeduplicator(false);
        String url = "http://example.com/page?";
        assertFalse(d.isDuplicate(url));
        d.markQueued(url);
        assertTrue(d.isDuplicate(url));
    }

    @Test
    public void testSingleParamNoValue() {
        // ?flag (no =) is treated as a token "flag"
        UrlDeduplicator d = new UrlDeduplicator(false);
        d.markQueued("http://example.com/page?flag");
        // Same param → dup
        assertTrue(d.isDuplicate("http://example.com/page?flag&extra=1"));
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

        // First visit: main page with primary ID
        String seed = "http://court.cz/subject.aspx?id=100";
        assertFalse(d.isDuplicate(seed));
        d.markQueued(seed);

        // Sort variants → dup
        assertTrue(d.isDuplicate("http://court.cz/subject.aspx?id=100&sort=date"));
        assertTrue(d.isDuplicate("http://court.cz/subject.aspx?id=100&sort=name&dir=asc"));

        // Different subject → not a dup
        assertFalse(d.isDuplicate("http://court.cz/subject.aspx?id=200"));
        d.markQueued("http://court.cz/subject.aspx?id=200");

        // Sort variant of the second subject: canonical is still {id=100} from the first visit.
        // ?id=200&sort=date does NOT contain id=100, so it is NOT recognized as a dup.
        // This is a known limitation: per-base canonicals come from the first-seen URL only.
        assertFalse(d.isDuplicate("http://court.cz/subject.aspx?id=200&sort=date"));

        // Document files with different IDs → not dups
        String doc1 = "http://court.cz/file.aspx?fileid=9477999";
        assertFalse(d.isDuplicate(doc1));
        d.markQueued(doc1);

        assertFalse(d.isDuplicate("http://court.cz/file.aspx?fileid=1234"));
        assertFalse(d.isDuplicate("http://court.cz/file.aspx?fileid=5678"));
    }
}
