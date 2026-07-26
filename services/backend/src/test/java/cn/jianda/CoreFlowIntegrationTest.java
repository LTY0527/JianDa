package cn.jianda;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-uploads"
})
@AutoConfigureMockMvc
class CoreFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AiClient aiClient;

    @BeforeEach
    void configureAi() {
        when(aiClient.analyze(anyString(), anyString(), anyString())).thenReturn(Map.of(
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
    }

    @Test
    void loginReturnsJwtAndCurrentUser() throws Exception {
        String token = login();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("org_admin"))
                .andExpect(jsonPath("$.data.organizationName").value("浦江街道社区服务中心"));
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

        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_REVIEW"));

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
                        .content("{\"title\":\"集成测试补贴指南\",\"category\":\"养老\",\"sourceName\":\"浦江街道社区服务中心\",\"sourceUrl\":\"https://example.org/test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").exists())
                .andReturn().getResponse().getContentAsString();
        String slug = objectMapper.readTree(publish).path("data").path("slug").asText();
        mvc.perform(get("/api/public/items/{slug}", slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("集成测试补贴指南"))
                .andExpect(jsonPath("$.data.generated.STEP_CARDS[0].title").value("准备材料"));
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

        when(aiClient.analyze(anyString(), anyString(), anyString())).thenReturn(Map.of(
                "fields", List.of(Map.of(
                        "field_type", "LOCATION", "label", "地点", "value", "第二页",
                        "page_no", 2, "segment_no", 1, "source_quote", "第二页真实正文", "confidence", 0.99)),
                "summary", List.of("真实摘要"), "plain_text", "真实通俗版",
                "steps", List.of(), "term_explanations", Map.of(), "warnings", List.of(),
                "audio_script", "真实语音稿"));
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isOk());
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

        when(aiClient.analyze(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("AI service unavailable"));
        mvc.perform(post("/api/documents/{id}/process", documentId).header("Authorization", auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AI 服务暂时不可用，任务已标记失败，可稍后重试"));
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processing_status").value("FAILED"));
        mvc.perform(get("/api/documents/{id}/jobs", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].error_message").isNotEmpty());
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
