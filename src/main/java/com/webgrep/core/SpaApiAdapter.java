package com.webgrep.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.net.ssl.SSLSocketFactory;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Angular SPA pages that load content via REST APIs and fetches their
 * content directly from the API, bypassing the need for JavaScript execution.
 *
 * <p>Known sites:
 * <ul>
 *   <li><b>infodeska.gov.cz</b> — Czech government official notice board.
 *       The Angular frontend replaced a crawlable static site. Postings and
 *       attached PDFs are now served via a JSON REST API at
 *       {@code /eudpub/api/v1/vyveseni/vyhledej} (POST).</li>
 * </ul>
 */
public class SpaApiAdapter {

    private static final Pattern INFODESKA_PATTERN =
            Pattern.compile("^https?://[^/]*infodeska\\.gov\\.cz/eudpub/uredni-deska/organizace/(\\w+)");

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";

    public record SpaResult(String text, List<String> links) {}

    public boolean matches(String url) {
        return INFODESKA_PATTERN.matcher(url).find();
    }

    /**
     * Fetches content for a recognised SPA URL via its REST API.
     * Returns {@code null} if no adapter matches or the request fails.
     */
    public SpaResult fetch(String pageUrl, Map<String, String> cookies,
                           int timeoutMs, int maxBodySize, SSLSocketFactory sslFactory) {
        try {
            Matcher m = INFODESKA_PATTERN.matcher(pageUrl);
            if (!m.find()) return null;
            String orgCode = m.group(1);
            URL u = new URL(pageUrl);
            String apiBase = u.getProtocol() + "://" + u.getHost() + "/eudpub/api/v1/vyveseni";
            return fetchInfodeska(apiBase, orgCode, cookies, timeoutMs, maxBodySize, sslFactory);
        } catch (Exception e) {
            return null;
        }
    }

    private SpaResult fetchInfodeska(String apiBase, String orgCode, Map<String, String> cookies,
                                     int timeoutMs, int maxBodySize, SSLSocketFactory sslFactory) throws Exception {
        String postBody = "{\"kodSubjektu\":\"" + orgCode + "\"}";
        Connection conn = Jsoup.connect(apiBase + "/vyhledej")
                .timeout(timeoutMs)
                .maxBodySize(maxBodySize)
                .ignoreContentType(true)
                .cookies(cookies)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .requestBody(postBody)
                .method(Connection.Method.POST);
        if (sslFactory != null) conn.sslSocketFactory(sslFactory);

        JSONArray array = new JSONArray(conn.execute().body());

        StringBuilder text = new StringBuilder();
        List<String> links = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            appendIfPresent(text, item, "subjekt");
            appendIfPresent(text, item, "popis");
            appendIfPresent(text, item, "znacka");
            appendIfPresent(text, item, "agenda");
            appendIfPresent(text, item, "umisteni");
            appendIfPresent(text, item, "pobocka");
            text.append('\n');

            JSONArray soubory = item.optJSONArray("soubory");
            if (soubory != null) {
                for (int j = 0; j < soubory.length(); j++) {
                    String fileId = soubory.getJSONObject(j).optString("id", "");
                    if (!fileId.isEmpty()) {
                        links.add(apiBase + "/soubor/" + fileId + "/download");
                    }
                }
            }
        }

        return new SpaResult(text.toString(), links);
    }

    private void appendIfPresent(StringBuilder sb, JSONObject obj, String field) {
        String value = obj.optString(field, "");
        if (!value.isEmpty() && !value.equals("null")) {
            sb.append(value).append(' ');
        }
    }
}
