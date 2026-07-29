package cn.jianda;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-async-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-async-uploads",
        "jianda.processing.async-enabled=true",
        "jianda.crawl.daily-ai-max-articles=1000",
        "jianda.crawl.daily-ai-max-tokens=10000000"
})
@AutoConfigureMockMvc
class DocumentProcessingAsyncIntegrationTest {
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
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenReturn(reviewableResult());
    }

    @Test
    void processReturnsImmediatelyReusesRunningJobAndEventuallyCompletes() throws Exception {
        String auth = "Bearer " + login();
        long documentId = createUploadedDocument(auth, "异步处理成功");
        CountDownLatch enteredAi = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenAnswer(invocation -> {
                    enteredAi.countDown();
                    if (!releaseAi.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test AI release timed out");
                    }
                    return reviewableResult();
                });

        Instant started = Instant.now();
        String first = mvc.perform(post("/api/documents/{id}/process", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.jobId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();
        long jobId = objectMapper.readTree(first).path("data").path("jobId").asLong();
        if (elapsedMs >= 1500) {
            throw new AssertionError("process endpoint blocked for " + elapsedMs + " ms");
        }
        if (!enteredAi.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("background AI task did not start");
        }

        mvc.perform(post("/api/documents/{id}/process", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobId))
                .andExpect(jsonPath("$.data.alreadyRunning").value(true));

        releaseAi.countDown();
        awaitJob(jobId, "SUCCEEDED");
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processing_status").value("WAITING_REVIEW"))
                .andExpect(jsonPath("$.data.content_kind").value("STANDARD_SPECIFICATION"));
        Integer moduleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generated_content WHERE document_id=? "
                        + "AND content_type='STANDARD_SECTIONS'",
                Integer.class, documentId);
        if (moduleCount == null || moduleCount != 1) {
            throw new AssertionError("expected one persisted STANDARD_SECTIONS module");
        }
    }

    @Test
    void backgroundFailureIsPersistedAfterSuccessfulSubmissionResponse() throws Exception {
        String auth = "Bearer " + login();
        long documentId = createUploadedDocument(auth, "异步处理失败");
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        String response = mvc.perform(post("/api/documents/{id}/process", documentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).path("data").path("jobId").asLong();

        awaitJob(jobId, "FAILED");
        mvc.perform(get("/api/documents/{id}/jobs", documentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].error_message").isNotEmpty());
    }

    private long createUploadedDocument(String auth, String title) throws Exception {
        String created = mvc.perform(post("/api/documents").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();
        MockMultipartFile file = new MockMultipartFile(
                "file", title + ".pdf", "application/pdf",
                "%PDF-test".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/documents/{id}/upload", documentId).file(file)
                        .header("Authorization", auth)
                        .param("manualText", "适用范围：社区养老服务。"))
                .andExpect(status().isOk());
        return documentId;
    }

    private void awaitJob(long jobId, String expectedStatus) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM processing_job WHERE id=?", String.class, jobId);
            if (expectedStatus.equals(status)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not reach " + expectedStatus);
    }

    private Map<String, Object> reviewableResult() {
        return Map.of(
                "fields", List.of(),
                "document_kind", "STANDARD_SPECIFICATION",
                "standard_sections", Map.of("scope", "适用于社区养老服务"),
                "summary", List.of("本标准适用于社区养老服务。"),
                "plain_text", "这份标准说明了社区养老服务的基本要求。",
                "steps", List.of(),
                "term_explanations", Map.of(),
                "warnings", List.of(),
                "audio_script", "本标准适用于社区养老服务。");
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"org_admin\",\"password\":\"Jianda@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        return data.path("token").asText();
    }
}
