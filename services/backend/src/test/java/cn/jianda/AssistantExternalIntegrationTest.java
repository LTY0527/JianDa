package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-assistant-external-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.assistant.external-enabled=true"
})
@AutoConfigureMockMvc
class AssistantExternalIntegrationTest {
    private static final AtomicBoolean FAIL = new AtomicBoolean();
    private static final AtomicBoolean HALLUCINATE = new AtomicBoolean();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>("");
    private static final HttpServer SERVER = startServer();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @DynamicPropertySource
    static void aiService(DynamicPropertyRegistry registry) {
        registry.add("jianda.ai-service-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @BeforeEach
    void prepare() {
        FAIL.set(false);
        HALLUCINATE.set(false);
        LAST_REQUEST.set("");
        jdbc.update("DELETE FROM assistant_query_event");
        jdbc.update("DELETE FROM published_item WHERE slug LIKE 'assistant-external-%'");
        jdbc.update("DELETE FROM source_document WHERE title LIKE '外部助手测试-%'");
        long published = insertDocument("外部助手测试-公开", "公安机关提醒：不要向陌生人提供短信验证码。");
        long withdrawn = insertDocument("外部助手测试-撤回", "撤回内容中的电话不应作为依据。");
        insertPublished(published, "assistant-external-published", "短信验证码反诈提醒", "不要提供短信验证码", "PUBLISHED");
        jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,source_quote,"
                        + "confidence,review_status) VALUES (?,'CONTACT','咨询电话','021-55556666',1,"
                        + "'咨询电话：021-55556666',1.0,'CONFIRMED')", published);
        insertPublished(withdrawn, "assistant-external-withdrawn", "已撤回提醒", "不应引用", "WITHDRAWN");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void aggregatesReadyAssistantStatusFromAiService() throws Exception {
        mvc.perform(get("/api/public/assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval").value("ready"))
                .andExpect(jsonPath("$.data.external").value("ready"))
                .andExpect(jsonPath("$.data.status").value("ready"));
    }

    @Test
    void aggregatesUnreachableAssistantStatusWhenAiServiceFails() throws Exception {
        FAIL.set(true);
        mvc.perform(get("/api/public/assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval").value("ready"))
                .andExpect(jsonPath("$.data.external").value("unreachable"))
                .andExpect(jsonPath("$.data.status").value("unreachable"));
    }

    @Test
    void returnsAiModeWithPublishedCitationAndStoresOnlySafeMetrics() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"陌生人索要短信验证码怎么办？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("ai"))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("[1]")))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-external-published"))
                .andExpect(jsonPath("$.data.factCards[0].type").value("phone"))
                .andExpect(jsonPath("$.data.factCards[0].value").value("021-55556666"));

        assertTrue(LAST_REQUEST.get().contains("assistant-external-published"));
        assertTrue(LAST_REQUEST.get().contains("021-55556666"));
        assertFalse(LAST_REQUEST.get().contains("assistant-external-withdrawn"));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_query_event WHERE mode='ai' AND citation_count=1 AND total_tokens=180",
                Integer.class) == 1);
    }

    @Test
    void fallsBackToRetrievalWhenExternalProviderFails() throws Exception {
        FAIL.set(true);
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"有哪些验证码反诈提醒？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-external-published"));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_query_event WHERE mode='retrieval' AND error_code='EXTERNAL_FALLBACK'",
                Integer.class) == 1);
    }

    @Test
    void usesGeneralAiOnlyForLowRiskQuestionWithoutPublishedEvidence() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"请用通俗语言解释什么是量子纠缠"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("general_ai"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer")
                        .value(org.hamcrest.Matchers.containsString("通用知识")));
        assertTrue(LAST_REQUEST.get().contains("量子纠缠"));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_query_event "
                        + "WHERE mode='general_ai' AND evidence_count=0 AND total_tokens=90",
                Integer.class) == 1);
    }

    @Test
    void loggedInResidentCanContinueAfterThirtyAssistantCallsWithoutDailyBlocking() throws Exception {
        String login = mvc.perform(post("/api/public/resident/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo_chen\",\"password\":\"Resident@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).path("data").path("token").asText();

        for (int index = 1; index <= 30; index++) {
            mvc.perform(post("/api/public/assistant/chat")
                            .header("X-Resident-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"请用通俗语言解释量子纠缠第" + index + "问\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mode").value("general_ai"));
        }

        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_query_event WHERE resident_user_id IS NOT NULL "
                        + "AND mode='general_ai'", Integer.class) == 30);
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_query_event WHERE error_code IN "
                        + "('GLOBAL_BUDGET_LIMIT','RESIDENT_BUDGET_LIMIT','GUEST_BUDGET_LIMIT')",
                Integer.class) == 0);
    }

    @Test
    void rejectsUnsupportedPhoneAndFallsBackToPublishedRetrieval() throws Exception {
        HALLUCINATE.set(true);
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"陌生人索要短信验证码怎么办？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.answer", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("021-12345678"))))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-external-published"));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_query_event WHERE mode='retrieval' "
                        + "AND error_code='EXTERNAL_FALLBACK'", Integer.class) == 1);
    }

    private long insertDocument(String title, String rawText) {
        jdbc.update("INSERT INTO source_document(organization_id,title,file_name,file_type,storage_path,raw_text,page_count,processing_status,created_by) "
                + "VALUES (1,?,NULL,'HTML',NULL,?,1,'PUBLISHED',1)", title, rawText);
        return jdbc.queryForObject("SELECT id FROM source_document WHERE title=?", Long.class, title);
    }

    private void insertPublished(long documentId, String slug, String title, String summary, String status) {
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,published_at,status,source_name,source_url,province,local_scope) "
                        + "VALUES (?,?,?,?, '反诈',1,?,?, '公安机关','https://example.gov.cn/source','全国','NATIONAL_SHARED')",
                documentId, slug, title, summary,
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 29, 10, 0)), status);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            com.sun.net.httpserver.HttpHandler handler = exchange -> {
                boolean statusRequest = exchange.getRequestURI().getPath().endsWith("/status");
                LAST_REQUEST.set(statusRequest ? "" : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int status = FAIL.get() ? 503 : 200;
                boolean general = exchange.getRequestURI().getPath().endsWith("/general-answer");
                String body = FAIL.get()
                        ? "{\"detail\":{\"error_code\":\"UPSTREAM_FAILED\",\"message\":\"暂时不可用\",\"retryable\":true}}"
                        : statusRequest
                        ? "{\"status\":\"ready\",\"external_enabled\":true,\"provider_configured\":true}"
                        : HALLUCINATE.get()
                        ? "{\"answer\":\"请拨打021-12345678并停止操作。[1]\",\"actions\":[],"
                        + "\"used_citation_indexes\":[1],\"model\":\"deepseek-v4-flash\","
                        + "\"request_id\":\"unsafe-request\",\"prompt_tokens\":140,"
                        + "\"completion_tokens\":40,\"total_tokens\":180,\"elapsed_ms\":25}"
                        : general
                        ? "{\"answer\":\"这是通用知识的通俗解释。\",\"actions\":[\"如需深入了解，请查阅可靠科普资料。\"],"
                        + "\"model\":\"deepseek-v4-flash\",\"request_id\":\"mock-general-1\","
                        + "\"prompt_tokens\":70,\"completion_tokens\":20,\"total_tokens\":90,\"elapsed_ms\":20}"
                        : "{\"answer\":\"立即停止操作，不要提供验证码。[1]\",\"actions\":[\"通过官方渠道核实。[1]\"],"
                        + "\"used_citation_indexes\":[1],\"model\":\"deepseek-v4-flash\",\"request_id\":\"mock-request-1\","
                        + "\"prompt_tokens\":140,\"completion_tokens\":40,\"total_tokens\":180,\"elapsed_ms\":25}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            };
            server.createContext("/internal/assistant/status", handler);
            server.createContext("/internal/assistant/answer", handler);
            server.createContext("/internal/assistant/general-answer", handler);
            server.start();
            return server;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
