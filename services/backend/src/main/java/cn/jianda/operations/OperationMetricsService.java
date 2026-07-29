package cn.jianda.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
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
        metrics.put("authoritySourceCount", count("SELECT COUNT(*) FROM source_registry"));
        metrics.put("discoveredArticleCount", count("SELECT COALESCE(SUM(discovered_count),0) FROM crawl_job"));
        metrics.put("successfulCrawlCount", count("SELECT COUNT(*) FROM crawl_job WHERE status='SUCCEEDED'"));
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
        int assistantQueries = count("SELECT COUNT(*) FROM assistant_query_event");
        int citedAnswers = count("SELECT COUNT(*) FROM assistant_query_event WHERE citation_count>0");
        metrics.put("assistantQueryCount", assistantQueries);
        metrics.put("citedAnswerRate", rate(citedAnswers, assistantQueries));
        int reviews = count("SELECT COUNT(*) FROM review_record");
        int edits = count("SELECT COUNT(*) FROM review_record "
                + "WHERE COALESCE(before_snapshot,'')<>COALESCE(after_snapshot,'')");
        metrics.put("manualEditRate", rate(edits, reviews));
        saveSnapshot(metrics);
        return metrics;
    }

    private int count(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
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
