package cn.jianda;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:jianda-public-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-public-uploads",
        "jianda.processing.async-enabled=false",
        "jianda.crawl.daily-ai-max-articles=1000",
        "jianda.crawl.daily-ai-max-tokens=10000000"
})
@AutoConfigureMockMvc
class PublicImportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiClient aiClient;

    @BeforeEach
    void configureAi() {
        jdbc.update("DELETE FROM ai_execution_audit");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_usage");
        jdbc.update("DELETE FROM ai_processing_queue");
        jdbc.update("UPDATE source_registry SET allow_image_cache=FALSE,image_cache_allowed=FALSE,"
                + "allow_image_candidates=FALSE "
                + "WHERE domain='www.news.cn'");
        when(aiClient.fetchImage(anyString())).thenReturn(
                new AiClient.ImageAsset(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47},
                        "image/png", 1200, 675));
        when(aiClient.previewWebArticle(anyString(), anyBoolean())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            return Map.ofEntries(
                    Map.entry("title", "老年人科学减重"),
                    Map.entry("source_name", "新华网"),
                    Map.entry("published_at", "2026-07-15T08:52:45+08:00"),
                    Map.entry("author", "新华社记者"),
                    Map.entry("cover_image_url", "https://www.news.cn/image/cover.png"),
                    Map.entry("cover_image_type", "ORIGINAL_COVER"),
                    Map.entry("image_alt_text", "老年人进行适量运动"),
                    Map.entry("image_width", 1200),
                    Map.entry("image_height", 675),
                    Map.entry("image_hash", "a".repeat(64)),
                    Map.entry("image_validated", true),
                    Map.entry("images", List.of(
                            Map.ofEntries(
                                    Map.entry("url", "https://www.news.cn/image/cover.png"),
                                    Map.entry("caption", "老年人进行适量运动"),
                                    Map.entry("discovery_method", "OPEN_GRAPH"),
                                    Map.entry("mime_type", "image/png"),
                                    Map.entry("width", 1200), Map.entry("height", 675),
                                    Map.entry("image_hash", "a".repeat(64)),
                                    Map.entry("image_cached", false), Map.entry("candidate_status", "VALID")),
                            Map.ofEntries(
                                    Map.entry("url", "https://www.news.cn/image/article.png"),
                                    Map.entry("caption", "正文运动示意"),
                                    Map.entry("discovery_method", "ARTICLE_IMAGE"),
                                    Map.entry("mime_type", "image/png"),
                                    Map.entry("width", 900), Map.entry("height", 600),
                                    Map.entry("image_hash", "e".repeat(64)),
                                    Map.entry("image_cached", false), Map.entry("candidate_status", "VALID")),
                            Map.ofEntries(
                                    Map.entry("url", "https://www.news.cn/image/logo.png"),
                                    Map.entry("caption", "网站 logo"),
                                    Map.entry("discovery_method", "ARTICLE_IMAGE"),
                                    Map.entry("mime_type", "image/png"),
                                    Map.entry("width", 120), Map.entry("height", 40),
                                    Map.entry("image_hash", "d".repeat(64)),
                                    Map.entry("image_cached", false), Map.entry("candidate_status", "VALID")))),
                    Map.entry("canonical_url", url),
                    Map.entry("content_preview", "老年人减重应保持吃动平衡。"),
                    Map.entry("extracted_text", "老年人减重应保持吃动平衡，不建议采取极端节食。出现持续不适时，应及时咨询医疗机构。"),
                    Map.entry("original_html", "<main><p>老年人减重应保持吃动平衡。</p></main>"),
                    Map.entry("content_hash",
                            url.contains("approved-cover") ? "c".repeat(64)
                                    : url.contains("candidates-without-cache") ? "9".repeat(64)
                                    : url.contains("organization-import") ? "d".repeat(64)
                                    : url.contains("platform-owned") ? "e".repeat(64)
                                    : url.contains("one-off") ? "f".repeat(64)
                                    : "b".repeat(64)),
                    Map.entry("content_kind", "HEALTH_EDUCATION"),
                    Map.entry("classification_confidence", 0.96),
                    Map.entry("robots_allowed", true),
                    Map.entry("robots_status", "ALLOWED"),
                    Map.entry("original_page_available", true),
                    Map.entry("warnings", List.of()));
        });
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenAnswer(invocation -> {
                    String rawText = invocation.getArgument(1);
                    boolean healthArticle = rawText.contains("极端节食");
                    return Map.of(
                            "fields", List.of(Map.of(
                                    "field_type", "WARNING",
                                    "label", "风险提示",
                                    "value", healthArticle ? "不要极端节食" : "不要共享屏幕或验证码",
                                    "page_no", 1,
                                    "source_quote", healthArticle
                                            ? "不建议采取极端节食"
                                            : "诱导下载陌生应用、开启屏幕共享或提供短信验证码",
                                    "confidence", 0.98)),
                            "summary", List.of("核实来电身份。", "不要共享屏幕或验证码。", "被骗后立即报警。"),
                            "plain_text", "陌生客服要求转账或验证码时，应挂断并通过官方渠道核实。",
                            "steps", List.of(),
                            "term_explanations", Map.of("安全账户", "诈骗分子虚构的转账说法。"),
                            "warnings", List.of("正规退款不会要求向安全账户转账。"),
                            "audio_script", "核实身份，不要转账，及时报警。");
                });
    }

    @Test
    void organizationCanImportOneOffUnregisteredPageWithoutTrustingDomain()
            throws Exception {
        String auth = "Bearer " + login("org_admin");
        String url = "https://one-off-unregistered.example/article";
        mvc.perform(post("/api/web-articles/preview-any")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trust_status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.external_source_verified").value(false))
                .andExpect(jsonPath("$.data.canonical_domain")
                        .value("one-off-unregistered.example"));

        String body = mvc.perform(post("/api/web-articles/import-once")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "url", url, "canonicalConfirmed", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        long documentId =
                objectMapper.readTree(body).path("data").path("documentId").asLong();
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT external_source_verified,source_authority_level,image_reviewed "
                        + "FROM source_document WHERE id=?",
                documentId);
        org.junit.jupiter.api.Assertions.assertFalse(
                Boolean.TRUE.equals(stored.get("external_source_verified")));
        org.junit.jupiter.api.Assertions.assertEquals(
                "UNVERIFIED", stored.get("source_authority_level"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crawl_job WHERE document_id=?",
                        Integer.class, documentId));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM source_registry "
                                + "WHERE domain='one-off-unregistered.example'",
                        Integer.class));
    }

    @Test
    void organizationCanImportPastedBodyWhenPublicPageCannotBeParsed()
            throws Exception {
        String auth = "Bearer " + login("org_admin");
        String url = "https://blocked-public.example/notice";
        String body = "这是机构人员从公开页面复制并核对的完整正文，仅作为未核验材料进入人工审核。";
        String imported = mvc.perform(post("/api/web-articles/import-pasted")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "url", url,
                                "title", "无法自动抓取的公开通知",
                                "sourceName", "待核验公开来源",
                                "body", body,
                                "contentKind", "SERVICE_NOTICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        long documentId =
                objectMapper.readTree(imported).path("data").path("documentId").asLong();
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT raw_text,external_source_verified,original_page_available,"
                        + "source_authority_level,cover_image_type,image_reviewed "
                        + "FROM source_document WHERE id=?",
                documentId);
        org.junit.jupiter.api.Assertions.assertEquals(body, stored.get("raw_text"));
        org.junit.jupiter.api.Assertions.assertFalse(
                Boolean.TRUE.equals(stored.get("external_source_verified")));
        org.junit.jupiter.api.Assertions.assertFalse(
                Boolean.TRUE.equals(stored.get("original_page_available")));
        org.junit.jupiter.api.Assertions.assertEquals(
                "UNVERIFIED", stored.get("source_authority_level"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "CATEGORY_DEFAULT", stored.get("cover_image_type"));
        org.junit.jupiter.api.Assertions.assertTrue(
                Boolean.TRUE.equals(stored.get("image_reviewed")));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_segment WHERE document_id=? AND text=?",
                        Integer.class, documentId, body));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crawl_job WHERE document_id=?",
                        Integer.class, documentId));
    }

    @Test
    void webPreviewIsWhitelistGatedAndImportIsDeduplicatedWithoutPublishing() throws Exception {
        String auth = "Bearer " + login("platform_admin");
        mvc.perform(post("/api/web-articles/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://untrusted.example/article\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("该域名不在权威来源白名单中"));

        String url = "https://www.news.cn/test/health.html";
        mvc.perform(post("/api/web-articles/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authority_level").value("B"))
                .andExpect(jsonPath("$.data.robots_allowed").value(true))
                .andExpect(jsonPath("$.data.cover_image_type").value("CATEGORY_DEFAULT"))
                .andExpect(jsonPath("$.data.cover_image_url").value(""));

        String imported = mvc.perform(post("/api/web-articles/import").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.imageReviewRequired").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(imported).path("data").path("documentId").asLong();
        mvc.perform(get("/api/documents/{id}", id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_type").value("WEB_ARTICLE"))
                .andExpect(jsonPath("$.data.content_kind").value("HEALTH_EDUCATION"))
                .andExpect(jsonPath("$.data.processing_status").value("UPLOADED"));
        mvc.perform(post("/api/web-articles/{id}/recrawl", id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentKind").value("HEALTH_EDUCATION"))
                .andExpect(jsonPath("$.data.contentChanged").value(false))
                .andExpect(jsonPath("$.data.cacheHit").value(true));
        mvc.perform(get("/api/documents/{id}/segments", id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(post("/api/documents/{id}/process", id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        verify(aiClient).analyze(
                anyString(), anyString(), anyString(), anyString(), anyList(),
                argThat(context -> "b".repeat(64).equals(context.get("content_sha256"))
                        && "web-v1.1".equals(context.get("prompt_version"))));

        mvc.perform(post("/api/web-articles/import").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/web-articles/{id}/cover/category-default", id).header("Authorization", auth))
                .andExpect(status().isOk());
    }

    @Test
    void webArticleImportUsesCurrentOrganizationAndDoesNotRequireOriginalFile() throws Exception {
        String platformAuth = "Bearer " + login("platform_admin");
        String organizationAuth = "Bearer " + login("org_admin");
        long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document", Long.class);

        String organizationUrl = "https://www.news.cn/test/organization-import.html";
        mvc.perform(post("/api/web-articles/preview").header("Authorization", organizationAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", organizationUrl))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("老年人科学减重"));
        org.junit.jupiter.api.Assertions.assertEquals(before,
                jdbc.queryForObject("SELECT COUNT(*) FROM source_document", Long.class));

        String imported = mvc.perform(post("/api/web-articles/import").header("Authorization", organizationAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", organizationUrl))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        long organizationDocumentId =
                objectMapper.readTree(imported).path("data").path("documentId").asLong();
        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT organization_id,created_by,source_type,storage_path FROM source_document WHERE id=?",
                organizationDocumentId);
        org.junit.jupiter.api.Assertions.assertEquals(
                jdbc.queryForObject("SELECT organization_id FROM staff_user WHERE username='org_admin'", Long.class),
                ((Number) saved.get("organization_id")).longValue());
        org.junit.jupiter.api.Assertions.assertEquals(
                jdbc.queryForObject("SELECT id FROM staff_user WHERE username='org_admin'", Long.class),
                ((Number) saved.get("created_by")).longValue());
        org.junit.jupiter.api.Assertions.assertEquals("WEB_ARTICLE", saved.get("source_type"));
        org.junit.jupiter.api.Assertions.assertNull(saved.get("storage_path"));
        mvc.perform(get("/api/documents/{id}", organizationDocumentId)
                        .header("Authorization", organizationAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_type").value("WEB_ARTICLE"));
        mvc.perform(get("/api/documents/{id}/original-file", organizationDocumentId)
                        .header("Authorization", organizationAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("网页文章没有 PDF 或图片原文件，请查看网页正文快照"));

        String platformUrl = "https://www.news.cn/test/platform-owned.html";
        String platformImported = mvc.perform(post("/api/web-articles/import")
                        .header("Authorization", platformAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", platformUrl))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long platformDocumentId =
                objectMapper.readTree(platformImported).path("data").path("documentId").asLong();
        mvc.perform(get("/api/documents/{id}", platformDocumentId)
                        .header("Authorization", organizationAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前机构无权访问该材料"));
        mvc.perform(get("/api/web-articles/sources").header("Authorization", organizationAuth))
                .andExpect(status().isForbidden());
    }

    @Test
    void imageCandidatesCanBeReviewedWhenPublicImageCacheIsDisabled() throws Exception {
        jdbc.update("UPDATE source_registry SET allow_image_cache=FALSE,image_cache_allowed=FALSE,"
                + "allow_image_candidates=TRUE "
                + "WHERE domain='www.news.cn'");
        org.mockito.Mockito.clearInvocations(aiClient);
        String auth = "Bearer " + login("platform_admin");
        String url = "https://www.news.cn/test/candidates-without-cache.html";

        mvc.perform(post("/api/web-articles/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allow_image_candidates").value(true))
                .andExpect(jsonPath("$.data.allow_image_cache").value(false))
                .andExpect(jsonPath("$.data.cover_image_type").value("ORIGINAL_COVER"))
                .andExpect(jsonPath("$.data.image_cached").value(false));
        verify(aiClient).previewWebArticle(url, true);

        String imported = mvc.perform(post("/api/web-articles/import").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageReviewRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(imported).path("data").path("documentId").asLong();
        String candidates = mvc.perform(get("/api/web-articles/{id}/image-candidates", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].image_cached").value(false))
                .andReturn().getResponse().getContentAsString();
        long candidateId = objectMapper.readTree(candidates).path("data").get(0).path("id").asLong();
        mvc.perform(post("/api/web-articles/image-candidates/{id}/approve", candidateId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"新华网原网页\",\"usageBasis\":\"仅确认来源，不允许本地缓存\"}"))
                .andExpect(status().isOk());
        verify(aiClient, never()).fetchImage(anyString());
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cover_image_type").value("CATEGORY_DEFAULT"))
                .andExpect(jsonPath("$.data.image_reviewed").value(true))
                .andExpect(jsonPath("$.data.image_cached").value(false));
    }

    @Test
    void permittedSourceMayUseOriginalCoverButStillRequiresManualReview() throws Exception {
        jdbc.update("UPDATE source_registry SET allow_image_cache=TRUE,image_cache_allowed=TRUE,"
                + "allow_image_candidates=TRUE "
                + "WHERE domain='www.news.cn'");
        String auth = "Bearer " + login("platform_admin");
        String url = "https://www.news.cn/test/approved-cover.html";

        mvc.perform(post("/api/web-articles/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cover_image_type").value("ORIGINAL_COVER"))
                .andExpect(jsonPath("$.data.image_cached").value(false));

        String imported = mvc.perform(post("/api/web-articles/import").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageReviewRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(imported).path("data").path("documentId").asLong();
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image_source_name").value("新华网"))
                .andExpect(jsonPath("$.data.image_source_url").value(url))
                .andExpect(jsonPath("$.data.image_alt_text").isNotEmpty())
                .andExpect(jsonPath("$.data.image_width").value(1200))
                .andExpect(jsonPath("$.data.image_height").value(675))
                .andExpect(jsonPath("$.data.image_hash").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.image_cached").value(false));
        Long reviewerId = jdbc.queryForObject(
                "SELECT id FROM staff_user WHERE username='platform_admin'", Long.class);
        jdbc.update("INSERT INTO review_record(document_id,reviewer_id,action,comment) "
                + "VALUES (?,?,'APPROVE','测试审核')", documentId, reviewerId);
        jdbc.update("INSERT INTO generated_content(document_id,content_type,title,plain_text) "
                + "VALUES (?,'SUMMARY','三句话看懂','这是一篇经过适老化处理的健康资讯。')", documentId);
        String publishBody = objectMapper.writeValueAsString(Map.of(
                "title", "健康资讯测试",
                "category", "健康",
                "sourceName", "新华网",
                "sourceUrl", url
        ));
        mvc.perform(post("/api/documents/{id}/publish", documentId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("第三方文章封面尚未人工确认，请确认图片来源或改用分类默认图"));

        mvc.perform(get("/api/web-articles/{id}/image-candidates", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].discovery_method").value("OPEN_GRAPH"))
                .andExpect(jsonPath("$.data[1].discovery_method").value("ARTICLE_IMAGE"));
        String candidatesBody = mvc.perform(get("/api/web-articles/{id}/image-candidates", documentId)
                        .header("Authorization", auth))
                .andReturn().getResponse().getContentAsString();
        long approvedCandidateId = objectMapper.readTree(candidatesBody).path("data").get(0).path("id").asLong();
        long rejectedCandidateId = objectMapper.readTree(candidatesBody).path("data").get(1).path("id").asLong();
        mvc.perform(post("/api/web-articles/image-candidates/{id}/reject", rejectedCandidateId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"版权范围不明确\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/web-articles/{id}/cover/confirm", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/web-articles/image-candidates/{id}/approve", approvedCandidateId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"新华网原网页\",\"usageBasis\":\"经人工核对可作为本条资讯封面\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/web-articles/{id}/cover/confirm", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        String published = mvc.perform(post("/api/documents/{id}/publish", documentId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String slug = objectMapper.readTree(published).path("data").path("slug").asText();
        mvc.perform(get("/api/public/items/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_url").value(url))
                .andExpect(jsonPath("$.data.image_source_url").value(url))
                .andExpect(jsonPath("$.data.cover_image_url")
                        .value("/api/public/items/" + slug + "/cover"))
                .andExpect(jsonPath("$.data.original_file_available").value(false));
        mvc.perform(get("/api/public/items/{slug}/cover", slug))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith("image/png"));
        mvc.perform(get("/api/public/items/{slug}/original-file", slug))
                .andExpect(status().isNotFound());

        when(aiClient.previewWebArticle(org.mockito.ArgumentMatchers.eq(url), anyBoolean())).thenReturn(Map.ofEntries(
                Map.entry("title", "老年人科学减重（更新版）"),
                Map.entry("source_name", "新华网"),
                Map.entry("published_at", "2026-07-16T08:52:45+08:00"),
                Map.entry("author", "新华社记者"),
                Map.entry("cover_image_url", "https://www.news.cn/image/cover-updated.png"),
                Map.entry("cover_image_type", "ORIGINAL_COVER"),
                Map.entry("image_alt_text", "老年人进行适量运动"),
                Map.entry("image_width", 1200),
                Map.entry("image_height", 675),
                Map.entry("image_hash", "f".repeat(64)),
                Map.entry("image_validated", true),
                Map.entry("images", List.of(Map.ofEntries(
                        Map.entry("url", "https://www.news.cn/image/cover-updated.png"),
                        Map.entry("caption", "更新版运动配图"),
                        Map.entry("discovery_method", "OPEN_GRAPH"), Map.entry("mime_type", "image/png"),
                        Map.entry("width", 1200), Map.entry("height", 675),
                        Map.entry("image_hash", "f".repeat(64)), Map.entry("image_cached", false),
                        Map.entry("candidate_status", "VALID")))),
                Map.entry("canonical_url", url),
                Map.entry("content_preview", "老年人减重应保持吃动平衡，新增了运动建议。"),
                Map.entry("extracted_text", "老年人减重应保持吃动平衡，不建议采取极端节食。新增了每日适量运动建议。"),
                Map.entry("original_html", "<main><p>老年人减重应保持吃动平衡，不建议采取极端节食。新增了每日适量运动建议。</p></main>"),
                Map.entry("content_hash", "f".repeat(64)),
                Map.entry("content_kind", "HEALTH_EDUCATION"),
                Map.entry("classification_confidence", 0.98),
                Map.entry("robots_allowed", true),
                Map.entry("robots_status", "ALLOWED"),
                Map.entry("original_page_available", true),
                Map.entry("warnings", List.of())));
        org.mockito.Mockito.clearInvocations(aiClient);
        String versioned = mvc.perform(post("/api/web-articles/{id}/recrawl", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previousDocumentId").value(documentId))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.aiQueueStatus").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.contentChanged").value(true))
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.oldHash").value("c".repeat(64)))
                .andExpect(jsonPath("$.data.newHash").value("f".repeat(64)))
                .andExpect(jsonPath("$.data.changeSummary").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long newDocumentId = objectMapper.readTree(versioned).path("data").path("documentId").asLong();
        org.mockito.Mockito.verify(aiClient, never()).analyze(
                anyString(), anyString(), anyString(), anyString(), anyList(), anyMap());
        org.junit.jupiter.api.Assertions.assertNotEquals(documentId, newDocumentId);
        mvc.perform(post("/api/web-articles/{id}/recrawl", newDocumentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentChanged").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(*) FROM source_document WHERE version_root_id=?",
                        Integer.class, documentId));
        org.junit.jupiter.api.Assertions.assertEquals(documentId,
                jdbc.queryForObject("SELECT previous_version_id FROM source_document WHERE id=?",
                        Long.class, newDocumentId));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT version_no FROM source_document WHERE id=?", Integer.class, newDocumentId));
        org.junit.jupiter.api.Assertions.assertEquals(documentId,
                jdbc.queryForObject("SELECT version_root_id FROM source_document WHERE id=?", Long.class, newDocumentId));
        org.junit.jupiter.api.Assertions.assertEquals("c".repeat(64),
                jdbc.queryForObject("SELECT old_content_hash FROM source_document WHERE id=?", String.class, newDocumentId));
        org.junit.jupiter.api.Assertions.assertEquals("f".repeat(64),
                jdbc.queryForObject("SELECT new_content_hash FROM source_document WHERE id=?", String.class, newDocumentId));
        org.junit.jupiter.api.Assertions.assertEquals("PUBLISHED",
                jdbc.queryForObject("SELECT processing_status FROM source_document WHERE id=?",
                        String.class, documentId));
        mvc.perform(get("/api/public/items/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document_id").value(documentId));
    }

    @Test
    void platformImportReviewPublishWithdrawAndOrganizationForbidden() throws Exception {
        mvc.perform(get("/api/public-sources").header("Authorization", "Bearer " + login("org_admin")))
                .andExpect(status().isForbidden());

        String auth = "Bearer " + login("platform_admin");
        mvc.perform(get("/api/public-sources").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(3)));
        mvc.perform(get("/api/public-sources/fixtures").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));

        String imported = mvc.perform(post("/api/public-sources/import/fixture/{fixtureId}", "anti-fraud-elderly-2026")
                        .header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(imported).path("data").path("documentId").asLong();

        mvc.perform(post("/api/public-sources/import/fixture/{fixtureId}", "anti-fraud-elderly-2026")
                        .header("Authorization", auth)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(post("/api/public-sources/imports/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));

        String fieldsBody = mvc.perform(get("/api/documents/{id}/fields", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode field : objectMapper.readTree(fieldsBody).path("data")) {
            mvc.perform(put("/api/documents/{documentId}/fields/{fieldId}", documentId, field.path("id").asLong())
                            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("value", field.path("field_value").asText(), "confirmed", true))))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/documents/{id}/review", documentId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"公开信息已核对原文\"}"))
                .andExpect(status().isOk());
        String published = mvc.perform(post("/api/documents/{id}/publish", documentId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"反诈提醒测试\",\"category\":\"反诈\",\"sourceName\":\"国家反诈中心\",\"sourceUrl\":\"https://www.mps.gov.cn/test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String slug = objectMapper.readTree(published).path("data").path("slug").asText();
        mvc.perform(get("/api/public/items/{slug}", slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generated.RISK_WARNING[0]").value("正规退款不会要求向安全账户转账。"));
        mvc.perform(post("/api/documents/{id}/withdraw", documentId).header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/items/{slug}", slug)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
