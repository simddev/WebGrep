package com.webgrep;

import com.webgrep.core.ContentExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // contain single-quoted hrefs - the regex fallback must catch them.
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

    // ── PDF pages ──────────────────────────────────────────────────────────────

    /**
     * Writes a PDF with one page per element of {@code pages}, each page holding the given lines.
     * Package-visible so the end-to-end tests can reuse it.
     */
    static Path writePdf(Path file, String[]... pages) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.font.PDType1Font font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            for (String[] lines : pages) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                             new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.setLeading(14);
                    cs.newLineAtOffset(72, 720);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    public void testPdfPagesAreSeparatedByFormFeeds() throws Exception {
        Path pdf = writePdf(Files.createTempFile("webgrep-pages", ".pdf"),
                new String[]{"page one text"}, new String[]{"page two text"}, new String[]{"page three text"});
        try {
            String text = extractor.extractTextFromBinary(Files.readAllBytes(pdf), pdf.getFileName().toString(), null);
            String[] pages = text.split("\f", -1);
            // A form feed between pages and one closing the last page: three pages, four parts.
            assertEquals("Unexpected page structure: " + text.replace("\f", "<FF>"), 4, pages.length);
            assertTrue(pages[0].contains("page one text"));
            assertTrue(pages[1].contains("page two text"));
            assertTrue(pages[2].contains("page three text"));
            assertTrue(pages[3].isBlank());
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    public void testOnePagePdfIsStillPaged() throws Exception {
        Path pdf = writePdf(Files.createTempFile("webgrep-onepage", ".pdf"),
                new String[]{"the attention", "mechanism is here"});
        try {
            String text = extractor.extractTextFromBinary(Files.readAllBytes(pdf), pdf.getFileName().toString(), null);
            List<com.webgrep.reporting.FileMatch> matches =
                    Main.findLineMatches(text, "attention mechanism", "default", new com.webgrep.core.MatchEngine());
            assertEquals(1, matches.size());
            assertEquals(1, matches.get(0).page());
            assertEquals("Layout blank lines must not shift the line number", 1, matches.get(0).line());
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    public void testPdfMatchesReportPageAndLine() throws Exception {
        Path pdf = writePdf(Files.createTempFile("webgrep-lines", ".pdf"),
                new String[]{"intro", "nothing"}, new String[]{"second page", "the attention", "mechanism is key"});
        try {
            String text = extractor.extractTextFromBinary(Files.readAllBytes(pdf), pdf.getFileName().toString(), null);
            List<com.webgrep.reporting.FileMatch> matches =
                    Main.findLineMatches(text, "attention mechanism", "default", new com.webgrep.core.MatchEngine());
            assertEquals(1, matches.size());
            assertEquals("The phrase is on page 2", 2, matches.get(0).page());
            assertEquals("It starts on the second printed line of the page", 2, matches.get(0).line());
            assertEquals("the attention mechanism is key", matches.get(0).snippet());
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    public void testPlainTextHasNoPageBreaks() {
        String text = extractor.extractTextFromBinary("line one\nline two".getBytes(StandardCharsets.UTF_8), "f.txt", "text/plain");
        assertFalse(text.contains("\f"));
    }
}
