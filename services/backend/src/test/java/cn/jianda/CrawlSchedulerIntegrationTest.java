package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import cn.jianda.ai.AiClient;
import cn.jianda.collector.CrawlScheduler;
import cn.jianda.common.BusinessException;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-scheduler-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.crawl.scheduler-enabled=false"
})
class CrawlSchedulerIntegrationTest {
    @Autowired CrawlScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiClient aiClient;
    private long sourceId;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM ai_execution_audit");
        jdbc.update("DELETE FROM ai_processing_queue");
        jdbc.update("DELETE FROM crawl_job_error");
        jdbc.update("DELETE FROM crawl_job WHERE source_registry_id IN "
                + "(SELECT id FROM source_registry WHERE domain='scheduler-fixture.example')");
        jdbc.update("DELETE FROM source_document WHERE source_domain='scheduler-fixture.example'");
        jdbc.update("DELETE FROM source_registry WHERE domain='scheduler-fixture.example'");
        jdbc.update("INSERT INTO source_registry(domain,source_name,source_type,authority_level,enabled,allow_auto_crawl,"
                + "homepage_url,section_url,discovery_mode,max_articles_per_run,recent_days,requires_manual_review) "
                + "VALUES ('scheduler-fixture.example','调度测试来源','GOVERNMENT','A',TRUE,TRUE,"
                + "'https://scheduler-fixture.example','https://scheduler-fixture.example/list','SECTION',2,7,TRUE)");
        sourceId = jdbc.queryForObject(
                "SELECT id FROM source_registry WHERE domain='scheduler-fixture.example'", Long.class);
        String article = "https://scheduler-fixture.example/article-1";
        when(aiClient.discoverArticles(anyLong(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("candidates", List.of(Map.of(
                        "canonical_url", article, "discovered_url", article, "title", "社区服务通知",
                        "published_time", LocalDate.now().minusDays(1).toString(), "discovery_method", "SECTION",
                        "discovery_page", "https://scheduler-fixture.example/list", "dedup_key", "scheduler-1")),
                        "errors", List.of()));
        when(aiClient.previewWebArticle(anyString(), anyBoolean(), anyBoolean())).thenReturn(Map.ofEntries(
                Map.entry("title", "社区服务通知"), Map.entry("source_name", "调度测试来源"),
                Map.entry("canonical_url", article), Map.entry("content_hash", "6".repeat(64)),
                Map.entry("extracted_text", "社区服务通知正文。"), Map.entry("content_kind", "COMMUNITY_SERVICE"),
                Map.entry("cover_image_type", "CATEGORY_DEFAULT"), Map.entry("images", List.of()),
                Map.entry("robots_status", "ALLOWED"), Map.entry("original_page_available", true)));
    }

    @Test
    void manualSchedulerTriggerUsesProductionPathAndKeepsAiWaitingForApproval() {
        Map<String, Object> result = scheduler.runSourceNow(sourceId);
        assertEquals(1, ((Number) result.get("discovered")).intValue());
        assertEquals(1, ((Number) result.get("added")).intValue());
        assertNotNull(result.get("nextRunAt"));
        assertEquals("SUCCESS", jdbc.queryForObject(
                "SELECT status FROM crawl_job WHERE id=?", String.class, result.get("jobId")));
        assertEquals("SCHEDULED", jdbc.queryForObject(
                "SELECT trigger_type FROM crawl_job WHERE id=?", String.class, result.get("jobId")));
        assertEquals("WAITING_APPROVAL", jdbc.queryForObject(
                "SELECT status FROM ai_processing_queue ORDER BY id DESC LIMIT 1", String.class));

        Map<String, Object> second = scheduler.runSourceNow(sourceId);
        assertEquals(0, ((Number) second.get("added")).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE source_domain='scheduler-fixture.example'", Integer.class));
    }

    @Test
    void disabledAutomaticSourceCannotBeTriggeredAsScheduler() {
        jdbc.update("UPDATE source_registry SET allow_auto_crawl=FALSE WHERE id=?", sourceId);
        assertThrows(BusinessException.class, () -> scheduler.runSourceNow(sourceId));
    }
}
