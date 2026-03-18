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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webgrep.utils.UrlUtils;

public class ContentExtractor {
    private final Tika tika;
    private static final int MAX_LINKS_PER_PAGE = 5000;

    public ContentExtractor(long maxBytes) {
        this.tika = new Tika();
        this.tika.setMaxStringLength((int) Math.min(maxBytes, Integer.MAX_VALUE));
    }

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

    public String extractTextFromBinary(byte[] body, String url, String contentType) {
        try (InputStream bis = new ByteArrayInputStream(body)) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, url);
            if (contentType != null) {
                metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
            }

            String content = tika.parseToString(bis, metadata);

            if (content == null || content.trim().isEmpty()) {
                try (InputStream bis2 = new ByteArrayInputStream(body)) {
                    content = tika.parseToString(bis2);
                }
            }
            return content;
        } catch (Throwable t) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public List<String> extractLinks(Document doc, byte[] rawBody, String baseUrl) {
        List<String> links = new ArrayList<>();
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

        if (links.size() < MAX_LINKS_PER_PAGE) {
            String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
            Pattern linkPattern = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher linkMatcher = linkPattern.matcher(bodyStr);
            while (linkMatcher.find() && links.size() < MAX_LINKS_PER_PAGE) {
                String href = linkMatcher.group(1);
                String normalizedLink = UrlUtils.normalizeUrl(href, baseUrl);
                if (!normalizedLink.isEmpty() && !UrlUtils.isIgnoredLink(normalizedLink)) {
                    if (!links.contains(normalizedLink)) {
                        links.add(normalizedLink);
                    }
                }
            }
        }
        return links;
    }
}
