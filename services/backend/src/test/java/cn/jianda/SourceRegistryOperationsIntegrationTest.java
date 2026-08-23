package cn.jianda;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import cn.jianda.collector.SourceRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-source-registry-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.crawl.scheduler-enabled=false",
        "jianda.crawl.auto-ai-enabled=false"
})
@AutoConfigureMockMvc
class SourceRegistryOperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryService service;
    @MockitoBean AiClient aiClient;

    private String platformAuth;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM source_registry_identity");
        jdbc.update("DELETE FROM source_registry WHERE domain LIKE 'phase93b-%'");
        platformAuth = "Bearer " + login("platform_admin");
        OffsetDateTime published = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        when(aiClient.discoverArticles(anyLong(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Map.of(
                        "candidates", List.of(Map.of(
                                "discovered_url", "https://www.news.cn/controlled-article.html",
                                "canonical_url", "https://www.news.cn/controlled-article.html",
                                "title", "受控采集测试文章",
                                "published_time", published.toString(),
                                "discovery_method", "SECTION",
                                "discovery_page", "https://www.news.cn/",
                                "content_kind_candidate", "GENERAL_NEWS",
                                "discovered_at", published.plusMinutes(1).toString(),
                                "dedup_key", "controlled-article")),
                        "errors", List.of()));
        when(aiClient.previewWebArticle(anyString(), anyBoolean())).thenReturn(Map.ofEntries(
                Map.entry("title", "受控采集测试文章"),
                Map.entry("source_name", "新华网"),
                Map.entry("published_at", "2026-07-29T10:00:00+08:00"),
                Map.entry("author", "测试记者"),
                Map.entry("cover_image_url", ""),
                Map.entry("cover_image_type", "CATEGORY_DEFAULT"),
                Map.entry("image_alt_text", "受控采集测试文章"),
                Map.entry("image_validated", false),
                Map.entry("images", List.of()),
                Map.entry("canonical_url", "https://www.news.cn/controlled-article.html"),
                Map.entry("content_preview", "这是一篇用于验证受控采集三段式入口的公开文章。"),
                Map.entry("extracted_text", "这是一篇用于验证受控采集三段式入口的公开文章。影子采集不会创建材料，立即采集会进入等待人工批准的 AI 队列。"),
                Map.entry("original_html", "<main><p>受控采集测试文章</p></main>"),
                Map.entry("content_hash", "8".repeat(64)),
                Map.entry("content_kind", "GENERAL_NEWS"),
                Map.entry("classification_confidence", 0.9),
                Map.entry("robots_allowed", true),
                Map.entry("robots_status", "ALLOWED"),
                Map.entry("original_page_available", true),
                Map.entry("warnings", List.of())));
    }

    @Test
    void sourceCrudDefaultsAreSafeAndSensitiveFieldsAreNotReturned() throws Exception {
        long id = create("phase93b-gov.example", "https://phase93b-gov.example/home");
        mvc.perform(get("/api/source-registries/{id}", id).header("Authorization", platformAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.allow_auto_ai").value(false))
                .andExpect(jsonPath("$.data.allow_image_candidates").value(false))
                .andExpect(jsonPath("$.data", not(hasKey("allow_image_cache"))))
                .andExpect(jsonPath("$.data", not(hasKey("lock_owner"))))
                .andExpect(jsonPath("$.data", not(hasKey("lock_until"))))
                .andExpect(jsonPath("$.data", not(hasKey("authorization"))))
                .andExpect(jsonPath("$.data", not(hasKey("cookie"))));

        mvc.perform(put("/api/source-registries/{id}", id).header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(payload(
                                "phase93b-gov.example", "https://phase93b-gov.example/new-home", "RSS", 12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.homepage_url").value("https://phase93b-gov.example/new-home"))
                .andExpect(jsonPath("$.data.discovery_mode").value("RSS"))
                .andExpect(jsonPath("$.data.max_articles_per_run").value(12));

        mvc.perform(put("/api/source-registries/{id}/enabled", id).header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(put("/api/source-registries/{id}/enabled", id).header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));

        mvc.perform(put("/api/source-registries/{id}/image-candidates-enabled", id)
                        .header("Authorization", platformAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allow_image_candidates").value(true));
        mvc.perform(put("/api/source-registries/{id}/image-candidates-enabled", id)
                        .header("Authorization", platformAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allow_image_candidates").value(false));

        mvc.perform(put("/api/source-registries/{id}/auto-crawl-enabled", id)
                        .header("Authorization", platformAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allow_auto_crawl").value(true))
                .andExpect(jsonPath("$.data.next_run_at").isNotEmpty());
        mvc.perform(put("/api/source-registries/{id}/auto-crawl-enabled", id)
                        .header("Authorization", platformAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allow_auto_crawl").value(false));
    }

    @Test
    void verifiedOfficialSourcesEnableImageFlowAndDachangRunsEveryTwelveHours() {
        Integer disabledImageSources = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_registry WHERE authority_level IN ('A','B') "
                        + "AND (allow_image_candidates=FALSE OR allow_image_cache=FALSE OR image_cache_allowed=FALSE)",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, disabledImageSources);
        Map<String, Object> dachang = jdbc.queryForMap(
                "SELECT enabled,allow_auto_crawl,allow_image_candidates,allow_image_cache,image_cache_allowed,"
                        + "schedule_mode,interval_hours FROM source_registry WHERE domain='xxgk.shbsq.gov.cn'");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, dachang.get("enabled"));
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, dachang.get("allow_auto_crawl"));
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, dachang.get("allow_image_candidates"));
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, dachang.get("allow_image_cache"));
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, dachang.get("image_cache_allowed"));
        org.junit.jupiter.api.Assertions.assertEquals("INTERVAL", dachang.get("schedule_mode"));
        org.junit.jupiter.api.Assertions.assertEquals(12, ((Number) dachang.get("interval_hours")).intValue());
    }

    @Test
    void quickPreviewRequiresAdministratorConfirmationAndStoresIdentityFingerprint() throws Exception {
        String url = "https://www.news.cn/controlled-article.html";
        mvc.perform(post("/api/source-registries/quick-preview").header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("www.news.cn"))
                .andExpect(jsonPath("$.data.robots_allowed").value(true))
                .andExpect(jsonPath("$.data.official_verified").value(false))
                .andExpect(jsonPath("$.data.source_identity_fingerprint").isNotEmpty());

        Map<String, Object> confirmation = Map.ofEntries(
                Map.entry("url", url),
                Map.entry("sourceName", "新华网"),
                Map.entry("sourceType", "OFFICIAL_MEDIA"),
                Map.entry("verificationNote", "已通过公开官网机构信息人工核对"),
                Map.entry("officialConfirmed", true),
                Map.entry("mode", "SAVE_MANUAL_SCAN"),
                Map.entry("imageUsagePolicy", "MANUAL_REVIEW"),
                Map.entry("imageUsageBasis", ""),
                Map.entry("autoApproveImages", false),
                Map.entry("imageCacheAllowed", false),
                Map.entry("continueImport", false));
        mvc.perform(post("/api/source-registries/quick-confirm").header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source.official_verified").value(true));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_registry_identity WHERE official_verified=TRUE",
                Integer.class) >= 1);

        confirmation = new java.util.HashMap<>(confirmation);
        confirmation.put("officialConfirmed", false);
        mvc.perform(post("/api/source-registries/quick-confirm").header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmation)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidProtocolsDomainMismatchAndOrganizationAdmin() throws Exception {
        mvc.perform(get("/api/source-registries").header("Authorization", "Bearer " + login("org_admin")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/source-registries").header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("phase93b-file.example", "file:///etc/passwd", "MANUAL", 5)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/source-registries").header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("phase93b-a.example", "https://phase93b-b.example/home", "MANUAL", 5)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void databaseLeasePreventsCompetitionAndRecoversAfterExpiry() throws Exception {
        long id = create("phase93b-lock.example", "https://phase93b-lock.example/home");
        mvc.perform(put("/api/source-registries/{id}/enabled", id).header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        assertTrue(service.acquireLease(id, "worker-a", Duration.ofMinutes(5)));
        assertFalse(service.acquireLease(id, "worker-b", Duration.ofMinutes(5)));
        assertTrue(service.releaseLease(id, "worker-a", "SUCCESS", null));
        assertTrue(service.acquireLease(id, "worker-b", Duration.ofMinutes(5)));

        jdbc.update("UPDATE source_registry SET lock_until=? WHERE id=?", LocalDateTime.now().minusMinutes(1), id);
        assertTrue(service.acquireLease(id, "worker-c", Duration.ofMinutes(5)));
        assertFalse(service.releaseLease(id, "worker-b", "FAILED", "旧持有者不能释放新租约"));
        assertTrue(service.releaseLease(id, "worker-c", "FAILED", "安全错误摘要"));
    }

    @Test
    void controlledEntrySeparatesDiscoveryShadowAndQueuedCollection() throws Exception {
        long sourceId = jdbc.queryForObject(
                "SELECT id FROM source_registry WHERE domain='www.news.cn'", Long.class);
        jdbc.update("UPDATE source_registry SET enabled=TRUE,allow_auto_ai=FALSE WHERE id=?", sourceId);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM source_document", Long.class);

        mvc.perform(post("/api/source-registries/{id}/discover", sourceId)
                        .header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"SECTION","entryUrl":"https://www.news.cn/"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].canonical_url")
                        .value("https://www.news.cn/controlled-article.html"));
        org.junit.jupiter.api.Assertions.assertEquals(before,
                jdbc.queryForObject("SELECT COUNT(*) FROM source_document", Long.class));

        mvc.perform(post("/api/source-registries/{id}/shadow", sourceId)
                        .header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://www.news.cn/controlled-article.html"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("受控采集测试文章"));
        org.junit.jupiter.api.Assertions.assertEquals(before,
                jdbc.queryForObject("SELECT COUNT(*) FROM source_document", Long.class));

        mvc.perform(post("/api/source-registries/{id}/collect", sourceId)
                        .header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://www.news.cn/controlled-article.html"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiQueueStatus").value("WAITING_APPROVAL"));
        org.junit.jupiter.api.Assertions.assertEquals(before + 1,
                jdbc.queryForObject("SELECT COUNT(*) FROM source_document", Long.class));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_processing_queue WHERE status='WAITING_APPROVAL' "
                        + "AND document_id=(SELECT id FROM source_document "
                        + "WHERE title='受控采集测试文章')",
                Integer.class) == 1);
    }

    private long create(String domain, String homepage) throws Exception {
        String body = mvc.perform(post("/api/source-registries").header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(payload(domain, homepage, "MANUAL", 5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.domain").value(domain))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private String payload(String domain, String homepage, String discoveryMode, int maxArticles) throws Exception {
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("name", "Phase 9.3-B 测试来源"), Map.entry("domain", domain),
                Map.entry("type", "GOVERNMENT"), Map.entry("authorityLevel", "A"),
                Map.entry("homepageUrl", homepage), Map.entry("rssUrl", ""),
                Map.entry("sitemapUrl", ""), Map.entry("sectionUrl", ""),
                Map.entry("discoveryMode", discoveryMode), Map.entry("dailyCrawlTime", "03:30"),
                Map.entry("maxArticlesPerRun", maxArticles), Map.entry("allowImageCandidates", false),
                Map.entry("allowAutoAi", false), Map.entry("dailyArticleBudget", 20), Map.entry("dailyTokenBudget", 50000)));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
