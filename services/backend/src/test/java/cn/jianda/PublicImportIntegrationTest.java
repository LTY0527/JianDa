package cn.jianda;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-public-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-public-uploads"
})
@AutoConfigureMockMvc
class PublicImportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AiClient aiClient;

    @BeforeEach
    void configureAi() {
        when(aiClient.analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap())).thenReturn(Map.of(
                "fields", List.of(Map.of("field_type", "WARNING", "label", "风险提示", "value", "不要提供验证码",
                        "page_no", 1, "source_quote", "不会索要银行卡密码和验证码。", "confidence", 0.98)),
                "summary", List.of("核实来电身份。", "不要共享屏幕或验证码。", "被骗后立即报警。"),
                "plain_text", "陌生客服要求转账或验证码时，应挂断并通过官方渠道核实。",
                "steps", List.of(), "term_explanations", Map.of("安全账户", "诈骗分子虚构的转账说法。"),
                "warnings", List.of("正规退款不会要求向安全账户转账。"),
                "audio_script", "核实身份，不要转账，及时报警。"));
    }

    @Test
    void platformImportReviewPublishWithdrawAndOrganizationForbidden() throws Exception {
        mvc.perform(get("/api/public-sources").header("Authorization", "Bearer " + login("org_admin")))
                .andExpect(status().isForbidden());

        String auth = "Bearer " + login("platform_admin");
        mvc.perform(get("/api/public-sources").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_REVIEW"));

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
