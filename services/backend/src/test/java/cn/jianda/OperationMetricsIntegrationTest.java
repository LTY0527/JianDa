package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-operation-metrics-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class OperationMetricsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void platformAdminReadsRealMetricsAndCreatesDailySnapshot() throws Exception {
        long publishedId = jdbc.queryForObject(
                "SELECT id FROM published_item WHERE status='PUBLISHED' ORDER BY id LIMIT 1",
                Long.class);
        int viewsBefore = count("SELECT COUNT(*) FROM content_engagement_event WHERE event_type='VIEW'");
        int queriesBefore = count("SELECT COUNT(*) FROM assistant_query_event");

        mvc.perform(post("/api/public/items/{id}/view", publishedId))
                .andExpect(status().isOk());
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"最近有哪些健康提醒？\"}"))
                .andExpect(status().isOk());

        int sources = count("SELECT COUNT(*) FROM source_registry");
        int published = count("SELECT COUNT(*) FROM published_item WHERE status='PUBLISHED'");
        mvc.perform(get("/api/operation-metrics")
                        .header("Authorization", "Bearer " + login("platform_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authoritySourceCount").value(sources))
                .andExpect(jsonPath("$.data.publishedCount").value(published))
                .andExpect(jsonPath("$.data.viewCount").value(viewsBefore + 1))
                .andExpect(jsonPath("$.data.assistantQueryCount").value(queriesBefore + 1))
                .andExpect(jsonPath("$.data.aiSuccessRate").isNumber())
                .andExpect(jsonPath("$.data.manualEditRate").isNumber());

        assertEquals(1, count(
                "SELECT COUNT(*) FROM daily_operation_snapshot WHERE snapshot_date=CURRENT_DATE"));
        String snapshot = jdbc.queryForObject(
                "SELECT metrics_json FROM daily_operation_snapshot WHERE snapshot_date=CURRENT_DATE",
                String.class);
        assertTrue(snapshot != null && snapshot.contains("\"publishedCount\":" + published));
    }

    @Test
    void organizationAdminCannotReadPlatformMetrics() throws Exception {
        mvc.perform(get("/api/operation-metrics")
                        .header("Authorization", "Bearer " + login("org_admin")))
                .andExpect(status().isForbidden());
    }

    private int count(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "Jianda@123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
