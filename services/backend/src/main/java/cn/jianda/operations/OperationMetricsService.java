package cn.jianda.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationMetricsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OperationMetricsService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> current() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Timestamp weeklyCutoff = Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS));
        metrics.put("authoritySourceCount", count("SELECT COUNT(*) FROM source_registry"));
        metrics.put("discoveredArticleCount", count("SELECT COALESCE(SUM(discovered_count),0) FROM crawl_job"));
        metrics.put("successfulCrawlCount", count(
                "SELECT COUNT(*) FROM crawl_job WHERE status IN ('SUCCESS','SUCCEEDED')"));
        metrics.put("duplicateCount", count("SELECT COALESCE(SUM(duplicate_count),0) FROM crawl_job"));
        metrics.put("waitingReviewCount", count("SELECT COUNT(*) FROM source_document WHERE processing_status='WAITING_REVIEW'"));
        metrics.put("publishedCount", count("SELECT COUNT(*) FROM published_item WHERE status='PUBLISHED'"));
        metrics.put("failedCount", count("SELECT COUNT(*) FROM source_document WHERE processing_status='FAILED'"));
        metrics.put("averageProcessingMs", decimal(
                "SELECT COALESCE(AVG(TIMESTAMPDIFF(MICROSECOND,started_at,finished_at)/1000),0) "
                        + "FROM processing_job WHERE started_at IS NOT NULL AND finished_at IS NOT NULL"));
        int aiRequests = count("SELECT COUNT(*) FROM ai_execution_audit");
        int aiSuccess = count("SELECT COUNT(*) FROM ai_execution_audit WHERE result='SUCCESS'");
        metrics.put("aiRequestCount", aiRequests);
        metrics.put("aiTokenCount", count("SELECT COALESCE(SUM(actual_tokens),0) FROM ai_execution_audit"));
        metrics.put("aiSuccessRate", rate(aiSuccess, aiRequests));
        metrics.put("viewCount", count("SELECT COUNT(*) FROM content_engagement_event WHERE event_type='VIEW'"));
        metrics.put("favoriteCount", count("SELECT COUNT(*) FROM favorite"));
        metrics.put("weeklyPublishedCount", count(
                "SELECT COUNT(*) FROM published_item WHERE status='PUBLISHED' "
                        + "AND published_at>=?", weeklyCutoff));
        metrics.put("weeklyViewCount", count(
                "SELECT COUNT(*) FROM content_engagement_event WHERE event_type='VIEW' "
                        + "AND created_at>=?", weeklyCutoff));
        metrics.put("weeklyListenCount", count(
                "SELECT COUNT(*) FROM usage_event WHERE event_type='CONTENT_LISTEN' "
                        + "AND created_at>=?", weeklyCutoff));
        metrics.put("weeklyFavoriteCount", count(
                "SELECT COUNT(*) FROM favorite WHERE created_at>=?", weeklyCutoff));
        metrics.put("weeklyReminderCount", count(
                "SELECT COUNT(*) FROM resident_reminder WHERE created_at>=?", weeklyCutoff));
        metrics.put("popularContent", popularContent());
        int assistantQueries = count("SELECT COUNT(*) FROM assistant_query_event");
        int citedAnswers = count("SELECT COUNT(*) FROM assistant_query_event WHERE citation_count>0");
        metrics.put("assistantQueryCount", assistantQueries);
        metrics.put("citedAnswerRate", rate(citedAnswers, assistantQueries));
        int reviews = count("SELECT COUNT(*) FROM review_record");
        int edits = count("SELECT COUNT(*) FROM review_record "
                + "WHERE COALESCE(before_snapshot,'')<>COALESCE(after_snapshot,'')");
        metrics.put("manualEditRate", rate(edits, reviews));
        metrics.put("todayDiscoveredCount", count(
                "SELECT COALESCE(SUM(discovered_count),0) FROM crawl_job "
                        + "WHERE discovered_at>=CURRENT_DATE"));
        metrics.put("todayCollectedCount", count(
                "SELECT COALESCE(SUM(added_count),0) FROM crawl_job "
                        + "WHERE discovered_at>=CURRENT_DATE"));
        metrics.put("todayDuplicateCount", count(
                "SELECT COALESCE(SUM(duplicate_count),0) FROM crawl_job "
                        + "WHERE discovered_at>=CURRENT_DATE"));
        metrics.put("todayFailedCount", count(
                "SELECT COALESCE(SUM(failed_count),0) FROM crawl_job "
                        + "WHERE discovered_at>=CURRENT_DATE"));
        metrics.put("pendingImageCandidateCount", count(
                "SELECT COUNT(*) FROM image_candidate WHERE review_status='PENDING'"));
        metrics.put("averageCrawlMs", decimal(
                "SELECT COALESCE(AVG(duration_ms),0) FROM crawl_job "
                        + "WHERE duration_ms>0"));
        metrics.put("averageAiMs", decimal(
                "SELECT COALESCE(AVG(TIMESTAMPDIFF(MICROSECOND,started_at,finished_at)/1000),0) "
                        + "FROM processing_job WHERE started_at IS NOT NULL AND finished_at IS NOT NULL"));
        metrics.put("tokenBudgetTotal", count(
                "SELECT COALESCE(SUM(daily_token_budget),0) FROM source_registry WHERE enabled=TRUE"));
        metrics.put("tokenUsedToday", count(
                "SELECT COALESCE(SUM(actual_tokens),0) FROM ai_budget_usage "
                        + "WHERE budget_date=CURRENT_DATE"));
        metrics.put("sources", sourceStatus());
        metrics.put("aiQueueByStatus", queueStatus());
        metrics.put("recentErrors", recentErrors());
        saveSnapshot(metrics);
        return metrics;
    }

    public List<Map<String, Object>> assistantEvents(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbc.queryForList(
                "SELECT id,question_category,mode,evidence_count,citation_count,success,model_id,"
                        + "prompt_tokens,completion_tokens,total_tokens,duration_ms,error_code,created_at "
                        + "FROM assistant_query_event ORDER BY id DESC LIMIT " + limit);
    }

    private List<Map<String, Object>> sourceStatus() {
        return jdbc.queryForList(
                "SELECT id,source_name,domain,enabled,last_status,last_crawled_at,"
                        + "next_run_at,last_error,failure_count FROM source_registry "
                        + "ORDER BY enabled DESC,last_crawled_at DESC,source_name");
    }

    private List<Map<String, Object>> queueStatus() {
        return jdbc.queryForList(
                "SELECT status,COUNT(*) item_count,COALESCE(SUM(estimated_tokens),0) estimated_tokens,"
                        + "COALESCE(SUM(actual_tokens),0) actual_tokens FROM ai_processing_queue "
                        + "GROUP BY status ORDER BY status");
    }

    private List<Map<String, Object>> recentErrors() {
        return jdbc.queryForList(
                "SELECT e.id,e.error_code,e.error_summary,e.processing_stage,e.failed_url,"
                        + "e.retryable,e.retry_count,e.created_at,r.source_name "
                        + "FROM crawl_job_error e JOIN source_registry r ON r.id=e.source_registry_id "
                        + "WHERE e.resolved_at IS NULL ORDER BY e.created_at DESC,e.id DESC LIMIT 10");
    }

    private List<Map<String, Object>> popularContent() {
        return jdbc.queryForList(
                "SELECT p.id,p.title,p.category,"
                        + "(SELECT COUNT(*) FROM content_engagement_event e WHERE e.published_item_id=p.id AND e.event_type='VIEW') view_count,"
                        + "(SELECT COUNT(*) FROM favorite f WHERE f.published_item_id=p.id) favorite_count,"
                        + "(SELECT COUNT(*) FROM usage_event u WHERE u.content_id=p.id AND u.event_type='CONTENT_LISTEN') listen_count "
                        + "FROM published_item p WHERE p.status='PUBLISHED' "
                        + "ORDER BY view_count DESC,favorite_count DESC,listen_count DESC,p.published_at DESC LIMIT 5");
    }

    private int count(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private int count(String sql, Object... parameters) {
        Number value = jdbc.queryForObject(sql, Number.class, parameters);
        return value == null ? 0 : value.intValue();
    }

    private long decimal(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : Math.round(value.doubleValue());
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    private void saveSnapshot(Map<String, Object> metrics) {
        try {
            String json = objectMapper.writeValueAsString(metrics);
            int existing = count("SELECT COUNT(*) FROM daily_operation_snapshot WHERE snapshot_date=CURRENT_DATE");
            if (existing == 0) {
                jdbc.update("INSERT INTO daily_operation_snapshot(snapshot_date,metrics_json) VALUES (CURRENT_DATE,?)", json);
            } else {
                jdbc.update("UPDATE daily_operation_snapshot SET metrics_json=?,created_at=CURRENT_TIMESTAMP "
                        + "WHERE snapshot_date=CURRENT_DATE", json);
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("运营快照序列化失败", exception);
        }
    }
}
