package cn.jianda;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import cn.jianda.ai.AiServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-uploads",
        "jianda.processing.async-enabled=false",
        "jianda.crawl.daily-ai-max-articles=1000",
        "jianda.crawl.daily-ai-max-tokens=10000000"
})
@AutoConfigureMockMvc
class CoreFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean AiClient aiClient;

    @BeforeEach
    void configureAi() {
        jdbc.update("DELETE FROM ai_execution_audit");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_usage");
        jdbc.update("DELETE FROM ai_processing_queue");
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap())).thenReturn(Map.of(
                "fields", List.of(
                        Map.of("field_type", "TARGET_AUDIENCE", "label", "适用对象", "value", "年满80周岁的本市户籍老人",
                                "page_no", 1, "source_quote", "补贴对象为年满八十周岁的本市户籍老人。", "confidence", 0.98),
                        Map.of("field_type", "MATERIAL", "label", "所需材料", "value", "身份证、户口簿和银行卡",
                                "page_no", 1, "source_quote", "申请材料包括身份证、户口簿和银行卡。", "confidence", 0.97)),
                "summary", List.of("符合条件的老人可以申请补贴。", "准备材料到社区办理。", "审核通过后发放。"),
                "plain_text", "符合条件的老人带齐材料到社区申请。",
                "steps", List.of(Map.of("order", 1, "title", "准备材料", "description", "准备身份证等材料。")),
                "term_explanations", Map.of("同类补贴", "用途相近且不能重复领取的补贴。"),
                "audio_script", "符合条件的老人可以申请补贴。"
        ));
        when(aiClient.extractText(any(Path.class), anyString(), anyString())).thenReturn(Map.of(
                "text", "第一页真实正文\n第二页真实正文",
                "page_count", 2,
                "segments", List.of(
                        Map.of("page_no", 1, "segment_no", 1, "text", "第一页真实正文",
                                "start_offset", 0, "end_offset", 7),
                        Map.of("page_no", 2, "segment_no", 1, "text", "第二页真实正文",
                                "start_offset", 8, "end_offset", 15))));
        when(aiClient.previewMetadata(any(Path.class), anyString(), anyString())).thenReturn(Map.of(
                "title", "秋冬季流感疫苗集中接种登记说明",
                "source_name", "海棠街道社区卫生服务中心",
                "document_number", "海卫预防〔2026〕09号",
                "source_type", "基层医疗卫生机构",
                "authority_status", "DOCUMENT_EVIDENCE",
                "confidence", 0.96,
                "evidence_quote", "海棠街道社区卫生服务中心",
                "evidence_type", "HEADER",
                "page_no", 1,
                "warnings", List.of()));
    }

    @Test
    void loginReturnsJwtAndCurrentUser() throws Exception {
        String token = login();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("org_admin"))
                .andExpect(jsonPath("$.data.organizationName").value("浦江街道社区服务中心"));
    }

    @Test
    void metadataPreviewDoesNotCreateDocumentOrLeaveTemporaryFile() throws Exception {
        String auth = "Bearer " + login();
        int before = objectMapper.readTree(mvc.perform(get("/api/documents").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").size();
        MockMultipartFile file = new MockMultipartFile(
                "file", "简达_模拟材料4_社区流感疫苗接种登记说明.pdf",
                "application/pdf", "%PDF-preview".getBytes());
        mvc.perform(multipart("/api/documents/metadata-preview").file(file).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("秋冬季流感疫苗集中接种登记说明"))
                .andExpect(jsonPath("$.data.source_name").value("海棠街道社区卫生服务中心"))
                .andExpect(jsonPath("$.data.authority_status").value("DOCUMENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.document_number").value("海卫预防〔2026〕09号"));
        int after = objectMapper.readTree(mvc.perform(get("/api/documents").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").size();
        org.junit.jupiter.api.Assertions.assertEquals(before, after);
        Path previewDir = Path.of("./target/test-uploads/.metadata-preview");
        if (Files.exists(previewDir)) {
            try (var files = Files.list(previewDir)) {
                org.junit.jupiter.api.Assertions.assertEquals(0, files.count());
            }
        }
    }

    @Test
    void generatedDefaultSecurityUserCannotAccessBusinessApi() throws Exception {
        String basic = Base64.getEncoder().encodeToString("user:any-password".getBytes());
        mvc.perform(get("/api/documents").header("Authorization", "Basic " + basic))
                .andExpect(status().isForbidden())
                .andExpect(result ->
                        org.junit.jupiter.api.Assertions.assertNotNull(
                                result.getResponse().getHeader("X-Request-Id")));
    }

    @Test
    void uploadProcessReviewPublishFlow() throws Exception {
        String token = login();
        String auth = "Bearer " + token;
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"集成测试补贴指南\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();

        MockMultipartFile file = new MockMultipartFile("file", "补贴通知.pdf", "application/pdf", "%PDF-demo".getBytes());
        mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(file).header("Authorization", auth)
                        .param("manualText", "补贴对象为年满八十周岁的本市户籍老人。\n申请材料包括身份证、户口簿和银行卡。"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.file_name").value("补贴通知.pdf"));
        byte[] original = mvc.perform(get("/api/documents/{id}/original-file", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentType().startsWith("application/pdf")))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotNull(
                        result.getResponse().getHeader("X-Content-SHA256")))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals("%PDF-demo".getBytes(), original);
        mvc.perform(get("/api/documents/{id}/original-file", documentId)
                        .header("Authorization", auth).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                        "%PDF".getBytes(), result.getResponse().getContentAsByteArray()));
        mvc.perform(get("/api/documents/{id}/original-file", documentId))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.jobId").isNumber());

        String fieldsBody = mvc.perform(get("/api/documents/{id}/fields", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode fields = objectMapper.readTree(fieldsBody).path("data");
        for (JsonNode field : fields) {
            mvc.perform(put("/api/documents/{documentId}/fields/{fieldId}", documentId, field.path("id").asLong())
                            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("value", field.path("field_value").asText(), "confirmed", true))))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/documents/{id}/review", documentId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"字段与原文一致\"}"))
                .andExpect(status().isOk());

        String publish = mvc.perform(post("/api/documents/{id}/publish", documentId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"集成测试补贴指南\",\"category\":\"养老\",\"sourceName\":\"浦江街道社区服务中心\",\"sourceUrl\":\"https://example.org/test\",\"allowPublicOriginal\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").exists())
                .andReturn().getResponse().getContentAsString();
        String slug = objectMapper.readTree(publish).path("data").path("slug").asText();
        mvc.perform(get("/api/public/items/{slug}", slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("集成测试补贴指南"))
                .andExpect(jsonPath("$.data.generated.STEP_CARDS[0].title").value("准备材料"))
                .andExpect(jsonPath("$.data.original_file_available").value(true));
        mvc.perform(get("/api/public/items/{slug}/original-file", slug).header("Range", "bytes=-4"))
                .andExpect(status().isPartialContent())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                        "demo".getBytes(), result.getResponse().getContentAsByteArray()));
        mvc.perform(get("/api/public/items/{slug}/original-file", slug)
                        .param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void pdfUploadPersistsRealPagesAndTraceableFieldSource() throws Exception {
        String auth = "Bearer " + login();
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"真实 PDF 追溯测试\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "真实材料.pdf", "application/pdf", "%PDF-test".getBytes());

        mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(pdf).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.raw_text").value("第一页真实正文\n第二页真实正文"))
                .andExpect(jsonPath("$.data.page_count").value(2));
        mvc.perform(get("/api/documents/{id}/segments", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].text").value("第一页真实正文"))
                .andExpect(jsonPath("$.data[1].page_no").value(2));

        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap())).thenReturn(Map.of(
                "fields", List.of(Map.of(
                        "field_type", "LOCATION", "label", "地点", "value", "第二页",
                        "page_no", 2, "segment_no", 1, "source_quote", "第二页真实正文", "confidence", 0.99)),
                "summary", List.of("真实摘要"), "plain_text", "真实通俗版",
                "steps", List.of(), "term_explanations", Map.of(), "warnings", List.of(),
                "audio_script", "真实语音稿"));
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> segmentsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).analyze(
                eq("真实 PDF 追溯测试"),
                eq("第一页真实正文\n第二页真实正文"),
                eq("guide"),
                eq("浦江街道社区服务中心"),
                segmentsCaptor.capture(),
                anyMap());
        List<Map<String, Object>> sentSegments = segmentsCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(2, sentSegments.size());
        org.junit.jupiter.api.Assertions.assertEquals(1, sentSegments.get(0).get("page_no"));
        org.junit.jupiter.api.Assertions.assertEquals("第一页真实正文", sentSegments.get(0).get("text"));
        org.junit.jupiter.api.Assertions.assertTrue(sentSegments.get(0).get("segment_id") instanceof Long);
        mvc.perform(get("/api/documents/{id}/fields", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].page_no").value(2))
                .andExpect(jsonPath("$.data[0].segment_id").isNumber())
                .andExpect(jsonPath("$.data[0].source_quote").value("第二页真实正文"));
    }

    @Test
    void aiFailureMarksDocumentAndJobFailedWithNaturalMessage() throws Exception {
        String auth = "Bearer " + login();
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"AI不可用验收材料\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        MockMultipartFile file = new MockMultipartFile(
                "file", "验收材料.png", "image/png", "image-content".getBytes());
        mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(file)
                        .header("Authorization", auth).param("manualText", "用于验证 AI 服务不可用时的任务状态。"))
                .andExpect(status().isOk());

        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenThrow(new IllegalStateException("AI service unavailable"));
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processing_status").value("FAILED"));
        mvc.perform(get("/api/documents/{id}/jobs", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].error_message").isNotEmpty());
    }

    @Test
    void generatedModulesWithoutFlatFieldsCanEnterReviewAndBeReprocessed() throws Exception {
        String auth = "Bearer " + login();
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"空字段重试测试\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        String sourceText = "咨询电话：021-5558 7301。";
        MockMultipartFile file = new MockMultipartFile(
                "file", "空字段重试.pdf", "application/pdf", "%PDF-test".getBytes());
        mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(file)
                        .header("Authorization", auth).param("manualText", sourceText))
                .andExpect(status().isOk());

        Map<String, Object> noFieldsResult = Map.of(
                        "fields", List.of(),
                        "summary", List.of("没有字段的摘要"),
                        "plain_text", "没有字段的通俗版",
                        "steps", List.of(),
                        "term_explanations", Map.of(),
                        "warnings", List.of(),
                        "audio_script", "没有字段的朗读稿");
        Map<String, Object> contactFieldResult = Map.of(
                "fields", List.of(Map.of(
                        "field_type", "CONTACT",
                        "label", "咨询电话",
                        "value", "021-5558 7301",
                        "source_quote", "咨询电话：021-5558 7301。",
                        "confidence", 0.95)),
                "summary", List.of("请按通知咨询。"),
                "plain_text", "请拨打通知中的电话咨询。",
                "steps", List.of(),
                "term_explanations", Map.of(),
                "warnings", List.of(),
                "audio_script", "请拨打通知中的电话咨询。");
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenReturn(noFieldsResult, contactFieldResult);
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processing_status").value("WAITING_REVIEW"))
                .andExpect(jsonPath("$.data.raw_text").value(sourceText));
        mvc.perform(get("/api/documents/{id}/jobs", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"));
        mvc.perform(get("/api/documents/{id}/fields", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/documents/{id}/generated", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)));
        mvc.perform(get("/api/documents/{id}/segments", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].text").value(sourceText));

        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(get("/api/documents/{id}/fields", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].source_quote").value("咨询电话：021-5558 7301。"));
    }

    @Test
    void rewriteSchemaFailureKeepsFactCheckpointAndRetrySkipsFactExtraction() throws Exception {
        String auth = "Bearer " + login();
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"阶段恢复测试\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        String sourceText = "咨询电话：021-5558 7301。";
        mvc.perform(multipart("/api/documents/{id}/upload", documentId)
                        .file(new MockMultipartFile("file", "恢复.pdf", "application/pdf", "%PDF".getBytes()))
                        .header("Authorization", auth).param("manualText", sourceText))
                .andExpect(status().isOk());
        Map<String, Object> fact = Map.of(
                "field_type", "CONTACT", "label", "咨询电话", "value", "021-5558 7301",
                "source_quote", sourceText, "page_no", 1, "segment_id", 1,
                "confidence", 0.95, "needs_human_review", false);
        Map<String, Object> facts = Map.of("prompt_version", "v1", "fields", List.of(fact), "sessions", List.of());
        Map<String, Object> checkpoint = Map.of(
                "prompt_version", "v1", "schema_version", "1.1", "model", "test-model",
                "response_fingerprint", "0123456789abcdef", "request_id", "req-fact",
                "fact_extract_ms", 12, "prompt_tokens", 10, "completion_tokens", 5,
                "total_tokens", 15, "facts", facts);
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenThrow(new AiServiceException(503, Map.of(
                        "error_code", "LLM_SCHEMA_VALIDATION_FAILED",
                        "message", "缺少必填字段：quick_summary",
                        "stage", "accessible_rewrite", "json_path", "$.quick_summary",
                        "request_id", "req-rewrite", "retryable", true,
                        "fact_checkpoint", checkpoint)));
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(get("/api/documents/{id}/fields", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        when(aiClient.rewrite(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "fields", List.of(fact), "summary", List.of("请按原文咨询。"),
                        "plain_text", "请按原文电话咨询。", "steps", List.of(),
                        "term_explanations", Map.of(), "warnings", List.of(),
                        "audio_script", "请按原文电话咨询。", "metrics", Map.of("total_tokens", 8)));
        mvc.perform(post("/api/documents/{id}/retry-rewrite", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_REVIEW"));
        verify(aiClient).rewrite(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap(), anyMap());
        verify(aiClient).analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap());
    }

    @Test
    void automaticBudgetUsageDoesNotBlockManualProcessingOrEraseExistingResults() throws Exception {
        String auth = "Bearer " + login();
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"预算保留结果测试\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        String source = "预算拒绝时必须保留已经审核中的结果。";
        mvc.perform(multipart("/api/documents/{id}/upload", documentId)
                        .file(new MockMultipartFile("file", "预算.pdf", "application/pdf", "%PDF".getBytes()))
                        .header("Authorization", auth).param("manualText", source))
                .andExpect(status().isOk());
        jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,source_quote,confidence) "
                + "VALUES (?,'TEST','既有字段','保留',1,?,0.9)", documentId, source);
        jdbc.update("INSERT INTO generated_content(document_id,content_type,title,plain_text) "
                + "VALUES (?,'SUMMARY','既有摘要','必须保留')", documentId);
        jdbc.update("INSERT INTO ai_budget_usage(budget_date,scope_type,scope_id,settled_articles) "
                + "VALUES (CURRENT_DATE,'GLOBAL',0,1000)");
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        for (int attempt = 0; attempt < 50; attempt++) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM processing_job WHERE document_id=? ORDER BY id DESC LIMIT 1",
                    String.class, documentId);
            if (!"PROCESSING".equals(status) && !"PENDING".equals(status)) break;
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.assertNotEquals("WAITING_BUDGET",
                jdbc.queryForObject("SELECT status FROM processing_job WHERE document_id=? ORDER BY id DESC LIMIT 1",
                        String.class, documentId));
    }

    @Test
    void uploadAcceptsImagesAndRejectsUnsupportedFileType() throws Exception {
        String auth = "Bearer " + login();
        for (String extension : List.of("png", "jpg")) {
            String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"图片上传验收-" + extension + "\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
            MockMultipartFile image = new MockMultipartFile(
                    "file", "材料." + extension, "image/" + extension, "image-content".getBytes());
            mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(image)
                            .header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.file_name").value("材料." + extension));
        }

        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"非法格式验收\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        MockMultipartFile text = new MockMultipartFile(
                "file", "材料.txt", "text/plain", "not-supported".getBytes());
        mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(text)
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅支持 PDF、PNG、JPG 文件"));
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"org_admin\",\"password\":\"Jianda@123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
