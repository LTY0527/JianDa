package cn.jianda.publicapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredWebSearchProvider implements WebSearchProvider {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String provider;
    private final String apiKey;
    private final URI endpoint;

    public ConfiguredWebSearchProvider(
            ObjectMapper objectMapper,
            @Value("${jianda.web-search.provider:disabled}") String provider,
            @Value("${jianda.web-search.api-key:}") String apiKey,
            @Value("${jianda.web-search.endpoint:https://api.tavily.com/search}") String endpoint) {
        this.objectMapper = objectMapper;
        this.provider = provider == null ? "disabled" : provider.trim().toLowerCase(Locale.ROOT);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = validateEndpoint(endpoint);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Status status() {
        if ("disabled".equals(provider)) {
            return new Status(provider, "disabled", "联网搜索未启用");
        }
        if (!"tavily".equals(provider)) {
            return new Status(provider, "degraded", "不支持的联网搜索 Provider");
        }
        if (apiKey.isBlank()) {
            return new Status(provider, "degraded", "联网搜索凭据未配置");
        }
        return new Status(provider, "ready", "联网搜索已配置");
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        if (!status().ready()) throw new IllegalStateException(status().message());
        int safeLimit = Math.max(1, Math.min(limit, 5));
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("query", query);
            request.put("search_depth", "basic");
            request.put("max_results", safeLimit);
            request.put("include_answer", false);
            request.put("include_raw_content", false);
            byte[] payload = objectMapper.writeValueAsBytes(request);
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("联网搜索服务返回 HTTP " + response.statusCode());
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("联网搜索响应大小异常");
            }
            Map<String, Object> parsed = objectMapper.readValue(
                    body, new TypeReference<Map<String, Object>>() {});
            return parseResults(parsed.get("results"), safeLimit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("联网搜索请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("联网搜索暂时不可用", exception);
        }
    }

    private List<SearchResult> parseResults(Object value, int limit) {
        if (!(value instanceof List<?> rows)) return List.of();
        List<SearchResult> results = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> item)) continue;
            String title = safeText(item.get("title"), 200);
            String url = safeUrl(item.get("url"));
            String snippet = safeText(item.get("content"), 800);
            if (title.isBlank() || url.isBlank() || snippet.isBlank()) continue;
            results.add(new SearchResult(title, url, snippet, URI.create(url).getHost()));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private static String safeText(Object value, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String safeUrl(Object value) {
        try {
            URI uri = URI.create(value == null ? "" : String.valueOf(value).trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) return "";
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static URI validateEndpoint(String value) {
        URI uri = URI.create(value == null ? "" : value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Web search endpoint must use HTTPS");
        }
        return uri;
    }
}
