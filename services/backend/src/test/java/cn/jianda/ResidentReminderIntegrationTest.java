package cn.jianda;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-resident-reminder-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ResidentReminderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    long itemId;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM resident_reminder");
        jdbc.update("DELETE FROM usage_event");
        jdbc.update("DELETE FROM extracted_field WHERE document_id IN (SELECT id FROM source_document WHERE title='大场镇服务目录测试')");
        jdbc.update("DELETE FROM published_item WHERE title='大场镇服务目录测试'");
        jdbc.update("DELETE FROM source_document WHERE title='大场镇服务目录测试'");
        jdbc.update("INSERT INTO source_document(organization_id,title,raw_text,page_count,processing_status,created_by) "
                + "VALUES (1,'大场镇服务目录测试','官方通知正文',1,'PUBLISHED',1)");
        long documentId = jdbc.queryForObject(
                "SELECT id FROM source_document WHERE title='大场镇服务目录测试'", Long.class);
        jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,source_quote,confidence) "
                + "VALUES (?,'LOCATION','地点','大场镇官方服务地点',1,'大场镇官方服务地点',0.99)", documentId);
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,source_name,source_url,"
                + "region_code,district,street_or_town,last_verified_at) VALUES (?,'resident-service-test','大场镇服务目录测试',"
                + "'官方可追溯服务通知','社区服务',1,'大场镇官方来源','https://example.gov.cn/official',"
                + "'310113102','宝山区','大场镇',CURRENT_TIMESTAMP)", documentId);
        itemId = jdbc.queryForObject("SELECT id FROM published_item WHERE slug='resident-service-test'", Long.class);
    }

    @Test
    void directoryUsesTraceablePublishedFieldsWithoutInventingMissingPhone() throws Exception {
        mvc.perform(get("/api/public/service-directory").param("regionCode", "310113102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("大场镇服务目录测试"))
                .andExpect(jsonPath("$.data[0].address").value("大场镇官方服务地点"))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data[0].source_url").value("https://example.gov.cn/official"));
    }

    @Test
    void reminderIsOwnedByAnonymousVisitorAndUsageIsMinimal() throws Exception {
        String body = "{\"reminderType\":\"DEADLINE\",\"remindAt\":\"2026-08-30T01:00:00Z\"}";
        mvc.perform(post("/api/public/items/{id}/reminder", itemId)
                        .header("X-Anonymous-User", "visitor-a")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminderType").value("DEADLINE"));
        mvc.perform(get("/api/public/reminders").header("X-Anonymous-User", "visitor-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
        mvc.perform(get("/api/public/reminders").header("X-Anonymous-User", "visitor-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        mvc.perform(post("/api/public/items/{id}/event/SERVICE_ADDRESS_COPY", itemId)
                        .header("X-Anonymous-User", "visitor-a"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE anonymous_session_id='visitor-a'", Integer.class));
        long reminderId = jdbc.queryForObject("SELECT id FROM resident_reminder", Long.class);
        mvc.perform(delete("/api/public/reminders/{id}", reminderId)
                        .header("X-Anonymous-User", "visitor-b"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/reminders").header("X-Anonymous-User", "visitor-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
    }
}
