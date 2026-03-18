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
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webgrep.utils.UrlUtils;

public class ContentExtractor {
    private final Tika tika;
    private static final int MAX_LINKS_PER_PAGE = 5000;
    private static final int TIKA_TIMEOUT_SECONDS = 30;
    private static final ExecutorService TIKA_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private static final Pattern LINK_PATTERN =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

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
            if (!normalizedLink.isEmpty() && !UrlUtils.isIgnoredLink(normalizedLink) && !links.contains(normalizedLink)) {
                links.add(normalizedLink);
            }
        }

        if (links.size() < MAX_LINKS_PER_PAGE) {
            String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
            Matcher linkMatcher = LINK_PATTERN.matcher(bodyStr);
            while (linkMatcher.find() && links.size() < MAX_LINKS_PER_PAGE) {
                String href = org.jsoup.parser.Parser.unescapeEntities(linkMatcher.group(1), true);
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
