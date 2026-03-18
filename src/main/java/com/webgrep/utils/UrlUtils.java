package com.webgrep.utils;

import java.text.Normalizer;
import java.net.URL;

public class UrlUtils {

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
            String protocol = url.getProtocol().equalsIgnoreCase("http") ? "https" : url.getProtocol().toLowerCase();
            String host = url.getHost().toLowerCase();
            if (host.isEmpty()) return "";

            int port = url.getPort();
            String path = url.getPath();
            String query = url.getQuery();

            if (path.isEmpty()) {
                path = "/";
            }
            path = path.replaceAll("/{2,}", "/");

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
            return urlString;
        }
    }

    public static boolean isIgnoredLink(String url) {
        String lower = url.toLowerCase();
        int hashIdx = lower.indexOf('#');
        if (hashIdx != -1) {
            lower = lower.substring(0, hashIdx);
        }

        int queryIdx = lower.indexOf('?');
        if (queryIdx != -1) {
            lower = lower.substring(0, queryIdx);
        }

        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt")) {
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
