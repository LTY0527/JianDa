package cn.jianda.collector;

import cn.jianda.ai.AiClient;
import cn.jianda.common.BusinessException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleDiscoveryService {
    private static final Set<String> METHODS = Set.of("RSS", "ATOM", "SITEMAP", "JSON_LD", "SECTION", "MIXED");
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final CrawlTaskService taskService;

    public ArticleDiscoveryService(JdbcTemplate jdbc, AiClient aiClient, CrawlTaskService taskService) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.taskService = taskService;
    }

    @Transactional
    public Map<String, Object> discover(long sourceId, String method, String entryUrl) {
        return discover(sourceId, method, entryUrl, null);
    }

    @Transactional
    public Map<String, Object> discover(long sourceId, String method, String entryUrl, DiscoveryOptions options) {
        Map<String, Object> source = enabledSource(sourceId);
        String selectedMethod = method == null ? String.valueOf(source.get("discovery_mode"))
                : method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(selectedMethod)) throw new BusinessException(400, "不支持的文章发现方式");
        String selectedEntry = entryUrl == null || entryUrl.isBlank()
                ? configuredEntry(source, selectedMethod) : normalizeAndCheck(entryUrl, source);
        Map<String, Object> response;
        try {
            response = aiClient.discoverArticles(sourceId, String.valueOf(source.get("homepage_url")), selectedEntry,
                    selectedMethod, ((Number) source.get("rate_limit")).intValue());
        } catch (RuntimeException exception) {
            throw new BusinessException(502, "文章发现入口暂时无法访问或解析");
        }
        Object rawCandidates = response.get("candidates");
        SanitizedCandidates sanitized = rawCandidates instanceof List<?> list
                ? sanitizeCandidates(sourceId, source, list)
                : new SanitizedCandidates(List.of(), 0, List.of());
        List<Map<String, Object>> candidates = sanitized.candidates();
        candidates = filterCandidates(candidates, options);
        int duplicates = 0;
        for (Map<String, Object> candidate : candidates) {
            if (taskService.hasCanonical(sourceId, String.valueOf(candidate.get("canonical_url")))) duplicates++;
        }
        List<?> errors = response.get("errors") instanceof List<?> list ? list : List.of();
        jdbc.update("UPDATE source_registry SET last_status=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                errors.isEmpty() ? "SUCCESS" : candidates.isEmpty() ? "FAILED" : "PARTIAL_SUCCESS",
                errors.isEmpty() ? null : safeError(errors), sourceId);
        return Map.of("sourceId", sourceId, "method", selectedMethod, "candidates", candidates,
                "duplicateCount", duplicates,
                "filtered_external_count", sanitized.filteredCount(),
                "filtered_external_domains", sanitized.filteredDomains(),
                "errors", errors.stream().limit(100).map(String::valueOf).toList());
    }

    private List<Map<String, Object>> filterCandidates(
            List<Map<String, Object>> candidates, DiscoveryOptions options) {
        if (options == null) options = new DiscoveryOptions(null, null, null, null, null);
        int recentDays = options.recentDays() == null ? 7 : options.recentDays();
        int maxArticles = options.maxArticles() == null ? 20 : options.maxArticles();
        if (!Set.of(1, 3, 7, 30).contains(recentDays)) {
            throw new BusinessException(400, "最近天数仅支持 1、3、7 或 30");
        }
        if (maxArticles < 1 || maxArticles > 100) {
            throw new BusinessException(400, "最大发现篇数必须在 1 到 100 之间");
        }
        List<String> includes = keywords(options.includeKeywords());
        List<String> excludes = keywords(options.excludeKeywords());
        LocalDate earliest = LocalDate.now().minusDays(recentDays);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String searchable = (text(candidate.get("title")) + " " + text(candidate.get("canonical_url")))
                    .toLowerCase(Locale.ROOT);
            if (!includes.isEmpty() && includes.stream().noneMatch(searchable::contains)) continue;
            if (excludes.stream().anyMatch(searchable::contains)) continue;
            LocalDate published = candidateDate(candidate.get("published_time"));
            if (published != null && published.isBefore(earliest)) continue;
            String canonical = text(candidate.get("canonical_url"));
            Integer imported = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM source_document WHERE canonical_url=?", Integer.class, canonical);
            Map<String, Object> enriched = new LinkedHashMap<>(candidate);
            enriched.put("imported", imported != null && imported > 0);
            enriched.put("duplicate", imported != null && imported > 0);
            enriched.put("has_previous_version", imported != null && imported > 0);
            if (Boolean.TRUE.equals(options.onlyUnimported()) && imported != null && imported > 0) continue;
            result.add(enriched);
            if (result.size() >= maxArticles) break;
        }
        return result;
    }

    private static List<String> keywords(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[,，;；\\s]+"))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().limit(20).toList();
    }

    private static LocalDate candidateDate(Object value) {
        String text = text(value);
        if (text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
            } catch (DateTimeParseException | IndexOutOfBoundsException ignoredAgain) {
                return null;
            }
        }
    }

    private Map<String, Object> enabledSource(long sourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,domain,allowed_hosts,homepage_url,rss_url,sitemap_url,section_url,discovery_mode,rate_limit,enabled "
                        + "FROM source_registry WHERE id=? AND enabled=TRUE", sourceId);
        if (rows.isEmpty()) throw new BusinessException(403, "来源不存在或尚未启用，不能执行文章发现");
        return rows.get(0);
    }

    private String configuredEntry(Map<String, Object> source, String method) {
        String key = switch (method) {
            case "RSS", "ATOM" -> "rss_url";
            case "SITEMAP" -> "sitemap_url";
            case "SECTION", "JSON_LD", "MIXED" -> "section_url";
            default -> "homepage_url";
        };
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(400, "来源未配置对应的文章发现入口");
        }
        return normalizeAndCheck(String.valueOf(value), source);
    }

    private static String normalizeAndCheck(String rawUrl, Map<String, Object> source) {
        try {
            URI uri = URI.create(rawUrl.trim()).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || !hostAllowed(uri.getHost(), source)) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "发现入口必须是来源完整域名下的 HTTP 或 HTTPS 地址");
        }
    }

    private static SanitizedCandidates sanitizeCandidates(
            long sourceId, Map<String, Object> source, List<?> raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        Set<String> filteredDomains = new java.util.LinkedHashSet<>();
        int filteredCount = 0;
        for (Object value : raw) {
            if (!(value instanceof Map<?, ?> candidate)) continue;
            String canonical = text(candidate.get("canonical_url"));
            String dedup = text(candidate.get("dedup_key"));
            if (canonical.isBlank() || dedup.isBlank() || !seen.add(dedup)) continue;
            String host;
            try {
                host = URI.create(canonical).getHost();
            } catch (IllegalArgumentException exception) {
                host = null;
            }
            if (host == null || !hostAllowed(host, source)) {
                filteredCount++;
                if (host != null && filteredDomains.size() < 5) {
                    filteredDomains.add(host.toLowerCase(Locale.ROOT));
                }
                continue;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("source_id", sourceId);
            for (String key : List.of("discovered_url", "canonical_url", "title", "published_time",
                    "discovery_method", "discovery_page", "content_kind_candidate", "discovered_at", "dedup_key")) {
                safe.put(key, candidate.get(key));
            }
            result.add(safe);
            if (result.size() >= 100) break;
        }
        return new SanitizedCandidates(
                result, filteredCount, List.copyOf(filteredDomains));
    }

    private static boolean hostAllowed(
            String rawHost, Map<String, Object> source) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        String domain = text(source.get("domain")).toLowerCase(Locale.ROOT);
        if (host.equals(domain) || host.endsWith("." + domain)) return true;
        return java.util.Arrays.stream(text(source.get("allowed_hosts"))
                        .split("[,，;；\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(allowed ->
                        host.equals(allowed) || host.endsWith("." + allowed));
    }

    private static String safeError(List<?> errors) {
        String value = errors.stream().limit(3).map(String::valueOf).reduce((a, b) -> a + "；" + b).orElse("");
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record DiscoveryOptions(Integer recentDays, Integer maxArticles, String includeKeywords,
            String excludeKeywords, Boolean onlyUnimported) {}

    private record SanitizedCandidates(
            List<Map<String, Object>> candidates,
            int filteredCount,
            List<String> filteredDomains) {}
}
