package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CrawlScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlScheduler.class);
    private static final String IDENTITY = "jianda-crawl-scheduler-v1";
    private final JdbcTemplate jdbc;
    private final CrawlProperties properties;
    private final ArticleDiscoveryService discoveryService;
    private final CrawlTaskService taskService;
    private final WebArticleService webArticleService;

    public CrawlScheduler(JdbcTemplate jdbc, CrawlProperties properties,
                          ArticleDiscoveryService discoveryService,
                          CrawlTaskService taskService, WebArticleService webArticleService) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.taskService = taskService;
        this.webArticleService = webArticleService;
    }

    @Scheduled(fixedDelayString = "${jianda.crawl.scheduler-poll-ms:60000}")
    public void poll() {
        if (!properties.schedulerEnabled()) return;
        for (Map<String, Object> source : dueSources()) {
            try {
                runSource(((Number) source.get("id")).longValue(), source);
            } catch (RuntimeException exception) {
                LOGGER.warn("crawl_scheduler_source_failed source_id={} error_type={}",
                        source.get("id"), exception.getClass().getSimpleName());
            }
        }
    }

    public Map<String, Object> runSourceNow(long sourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM source_registry WHERE id=? AND enabled=TRUE AND allow_auto_crawl=TRUE",
                sourceId);
        if (rows.isEmpty()) {
            throw new BusinessException(400, "来源未启用自动采集，不能执行调度验收");
        }
        return runSource(sourceId, rows.get(0));
    }

    private List<Map<String, Object>> dueSources() {
        return jdbc.queryForList("SELECT * FROM source_registry WHERE enabled=TRUE AND allow_auto_crawl=TRUE "
                + "AND (next_run_at IS NULL OR next_run_at<=CURRENT_TIMESTAMP) ORDER BY id")
                .stream().limit(Math.max(1, properties.globalMaxArticlesPerRun())).toList();
    }

    private Map<String, Object> runSource(long sourceId, Map<String, Object> source) {
        String method = text(source.get("discovery_mode"));
        String entry = entry(source, method);
        long jobId = taskService.createBatch(sourceId, entry, "SCHEDULED", method, null, IDENTITY);
        String owner = IDENTITY + "-" + UUID.randomUUID();
        taskService.start(jobId, owner);
        int discovered = 0;
        int added = 0;
        int duplicates = 0;
        List<CrawlTaskService.Failure> failures = new ArrayList<>();
        try {
            int maxArticles = Math.min(number(source.get("max_articles_per_run"), 5),
                    Math.max(1, properties.globalMaxArticlesPerRun()));
            Map<String, Object> result = discoveryService.discover(sourceId, method, entry,
                    new ArticleDiscoveryService.DiscoveryOptions(
                            number(source.get("recent_days"), 7), maxArticles,
                            text(source.get("include_keywords")), text(source.get("exclude_keywords")), true));
            List<?> candidates = result.get("candidates") instanceof List<?> list ? list : List.of();
            discovered = candidates.size();
            for (Object candidateValue : candidates) {
                if (!(candidateValue instanceof Map<?, ?> candidate)) continue;
                String url = text(candidate.get("canonical_url"));
                if (url.isBlank()) continue;
                try {
                    webArticleService.importArticle(url, schedulerUser());
                    added++;
                } catch (BusinessException exception) {
                    if (exception.getCode() == 409) duplicates++;
                    else failures.add(new CrawlTaskService.Failure(
                            url, "IMPORT", "IMPORT_FAILED", exception.getMessage(), exception.getCode() >= 500));
                }
            }
        } catch (RuntimeException exception) {
            failures.add(new CrawlTaskService.Failure(entry, "DISCOVERY", "DISCOVERY_FAILED",
                    exception.getMessage(), true));
        } finally {
            taskService.finish(jobId, owner,
                    new CrawlTaskService.Counts(discovered, added, duplicates, 0, failures.size()), failures);
            jdbc.update("UPDATE source_registry SET next_run_at=? WHERE id=?", nextRun(source), sourceId);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("sourceId", sourceId);
        response.put("schedulerIdentity", IDENTITY);
        response.put("discovered", discovered);
        response.put("added", added);
        response.put("duplicates", duplicates);
        response.put("failed", failures.size());
        response.put("nextRunAt", jdbc.queryForObject(
                "SELECT next_run_at FROM source_registry WHERE id=?", Timestamp.class, sourceId));
        return response;
    }

    private AuthUser schedulerUser() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT u.id,u.organization_id,u.username,u.display_name,u.role,o.name organization_name "
                        + "FROM staff_user u JOIN organization o ON o.id=u.organization_id "
                        + "WHERE u.role='PLATFORM_ADMIN' AND u.status='ACTIVE' ORDER BY u.id LIMIT 1");
        return new AuthUser(((Number) row.get("id")).longValue(),
                ((Number) row.get("organization_id")).longValue(), text(row.get("username")),
                text(row.get("display_name")), text(row.get("role")), text(row.get("organization_name")));
    }

    private static Timestamp nextRun(Map<String, Object> source) {
        ZoneId zone;
        try {
            zone = ZoneId.of(text(source.get("schedule_timezone")));
        } catch (RuntimeException ignored) {
            zone = ZoneId.of("Asia/Shanghai");
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        if ("INTERVAL".equalsIgnoreCase(text(source.get("schedule_mode")))) {
            return Timestamp.valueOf(now.plusHours(number(source.get("interval_hours"), 24)).toLocalDateTime());
        }
        LocalTime time;
        try {
            time = LocalTime.parse(text(source.get("daily_crawl_time")));
        } catch (RuntimeException ignored) {
            time = LocalTime.of(3, 30);
        }
        ZonedDateTime next = now.toLocalDate().atTime(time).atZone(zone);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Timestamp.valueOf(next.toLocalDateTime());
    }

    private static String entry(Map<String, Object> source, String method) {
        String key = switch (method.toUpperCase()) {
            case "RSS", "ATOM" -> "rss_url";
            case "SITEMAP" -> "sitemap_url";
            case "SECTION", "JSON_LD", "MIXED" -> "section_url";
            default -> "homepage_url";
        };
        String value = text(source.get(key));
        return value.isBlank() ? text(source.get("homepage_url")) : value;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
