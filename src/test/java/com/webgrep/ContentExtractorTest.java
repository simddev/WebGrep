package com.webgrep;

import com.webgrep.core.ContentExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ContentExtractorTest {

    private final ContentExtractor extractor = new ContentExtractor(10 * 1024 * 1024);

    @Test
    public void testExtractTextFromHtml() {
        Document doc = Jsoup.parse(
            "<html><head><title>Test Title</title>" +
            "<meta name='description' content='A test page'></head>" +
            "<body><p>Hello world</p></body></html>");
        String text = extractor.extractTextFromHtml(doc);
        assertTrue(text.contains("Test Title"));
        assertTrue(text.contains("Hello world"));
        assertTrue(text.contains("A test page"));
    }

    @Test
    public void testHttpNormalizedToHttps() {
        // http:// and https:// links to the same path must normalize to https:// so dedup works
        String html = "<html><body>" +
            "<a href=\"http://example.com/page\">HTTP Link</a>" +
            "<a href=\"https://example.com/page\">HTTPS Link</a>" +
            "</body></html>";
        Document doc = Jsoup.parse(html, "https://example.com");
        byte[] raw = html.getBytes(StandardCharsets.UTF_8);
        List<String> links = extractor.extractLinks(doc, raw, "https://example.com");
        assertTrue(links.stream().allMatch(l -> l.startsWith("https://")));
        // Only one unique URL after normalization
        assertEquals(1, links.size());
    }

    @Test
    public void testAmpEntitiesDecodedInRawFallback() {
        // Raw HTML has &amp; in href query strings; the regex fallback must decode them
        // so they match the Jsoup-decoded version and not create duplicate entries
        String emptyDoc = "<html><body></body></html>";
        String rawHtml = "<html><body><a href='https://example.com/page?a=1&amp;b=2'>Link</a></body></html>";
        Document doc = Jsoup.parse(emptyDoc, "https://example.com");
        byte[] raw = rawHtml.getBytes(StandardCharsets.UTF_8);
        List<String> links = extractor.extractLinks(doc, raw, "https://example.com");
        assertFalse(links.stream().anyMatch(l -> l.contains("&amp;")));
        assertTrue(links.stream().anyMatch(l -> l.contains("a=1&b=2")));
    }

    @Test
    public void testExtractLinksDoubleQuotes() {
        String html = "<html><body>" +
            "<a href=\"http://example.com/page1\">Link</a>" +
            "<a href=\"http://example.com/report.pdf\">PDF</a>" +
            "</body></html>";
        Document doc = Jsoup.parse(html, "http://example.com");
        byte[] raw = html.getBytes(StandardCharsets.UTF_8);
        List<String> links = extractor.extractLinks(doc, raw, "http://example.com");
        assertTrue(links.stream().anyMatch(l -> l.contains("page1")));
        assertTrue(links.stream().anyMatch(l -> l.contains("report.pdf")));
    }

    @Test
    public void testExtractLinksSingleQuotesInRawFallback() {
        // Simulate malformed HTML where Jsoup finds no <a> tags, but raw bytes
        // contain single-quoted hrefs — the regex fallback must catch them.
        String emptyDoc = "<html><body></body></html>";
        String rawWithSingleQuotes =
            "<html><body><a href='http://example.com/single-page'>Link</a>" +
            "<a href='http://example.com/doc.pdf'>PDF</a></body></html>";
        Document doc = Jsoup.parse(emptyDoc, "http://example.com");
        byte[] raw = rawWithSingleQuotes.getBytes(StandardCharsets.UTF_8);
        List<String> links = extractor.extractLinks(doc, raw, "http://example.com");
        assertTrue(links.stream().anyMatch(l -> l.contains("single-page")));
        assertTrue(links.stream().anyMatch(l -> l.contains("doc.pdf")));
    }

    @Test
    public void testExtractLinksIgnoresStaticAssets() {
        String html = "<html><body>" +
            "<a href=\"http://example.com/page\">Page</a>" +
            "<a href=\"http://example.com/style.css\">CSS</a>" +
            "<a href=\"http://example.com/image.png\">Image</a>" +
            "<a href=\"http://example.com/font.woff2\">Font</a>" +
            "</body></html>";
        Document doc = Jsoup.parse(html, "http://example.com");
        byte[] raw = html.getBytes(StandardCharsets.UTF_8);
        List<String> links = extractor.extractLinks(doc, raw, "http://example.com");
        assertTrue(links.stream().anyMatch(l -> l.contains("/page")));
        assertFalse(links.stream().anyMatch(l -> l.endsWith(".css")));
        assertFalse(links.stream().anyMatch(l -> l.endsWith(".png")));
        assertFalse(links.stream().anyMatch(l -> l.endsWith(".woff2")));
    }

    @Test
    public void testExtractLinksKeepsPdfAndDocx() {
        String html = "<html><body>" +
            "<a href=\"http://example.com/report.pdf\">PDF</a>" +
            "<a href=\"http://example.com/manual.docx\">DOCX</a>" +
            "<a href=\"http://example.com/notes.txt\">TXT</a>" +
            "</body></html>";
        Document doc = Jsoup.parse(html, "http://example.com");
        byte[] raw = html.getBytes(StandardCharsets.UTF_8);
        List<String> links = extractor.extractLinks(doc, raw, "http://example.com");
        assertTrue(links.stream().anyMatch(l -> l.endsWith(".pdf")));
        assertTrue(links.stream().anyMatch(l -> l.endsWith(".docx")));
        assertTrue(links.stream().anyMatch(l -> l.endsWith(".txt")));
    }

    @Test
    public void testExtractTextFromBinaryPlainText() {
        String content = "This is a plain text document about WebGrep crawling.";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String extracted = extractor.extractTextFromBinary(bytes, "file.txt", "text/plain");
        assertTrue(extracted.contains("WebGrep"));
    }

    @Test
    public void testExtractTextFromBinaryFallsBackOnUnknownType() {
        byte[] bytes = "some raw content".getBytes(StandardCharsets.UTF_8);
        String extracted = extractor.extractTextFromBinary(bytes, "file.bin", "application/octet-stream");
        assertNotNull(extracted);
        assertFalse(extracted.isEmpty());
    }
}
