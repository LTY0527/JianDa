package cn.jianda;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:jianda-article-discovery-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ArticleDiscoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiClient aiClient;

    private long enabledId;
    private long disabledId;
    private String auth;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM source_registry WHERE domain LIKE 'discovery-fixture-%'");
        jdbc.update("INSERT INTO source_registry(domain,source_name,source_type,authority_level,enabled,homepage_url,rss_url,"
                + "discovery_mode,rate_limit,allow_auto_ai,requires_manual_review) VALUES "
                + "('discovery-fixture-enabled.example','离线发现来源','GOVERNMENT','A',TRUE,"
                + "'https://discovery-fixture-enabled.example','https://discovery-fixture-enabled.example/rss.xml','RSS',2,FALSE,TRUE)");
        jdbc.update("INSERT INTO source_registry(domain,source_name,source_type,authority_level,enabled,homepage_url,rss_url,"
                + "discovery_mode,rate_limit,allow_auto_ai,requires_manual_review) VALUES "
                + "('discovery-fixture-disabled.example','停用发现来源','GOVERNMENT','A',FALSE,"
                + "'https://discovery-fixture-disabled.example','https://discovery-fixture-disabled.example/rss.xml','RSS',2,FALSE,TRUE)");
        enabledId = jdbc.queryForObject("SELECT id FROM source_registry WHERE domain='discovery-fixture-enabled.example'", Long.class);
        jdbc.update("UPDATE source_registry SET allowed_hosts='approved-cdn.example' WHERE id=?", enabledId);
        disabledId = jdbc.queryForObject("SELECT id FROM source_registry WHERE domain='discovery-fixture-disabled.example'", Long.class);
        auth = "Bearer " + login("platform_admin");
        when(aiClient.discoverArticles(anyLong(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Map.of(
                        "candidates", List.of(
                                candidate("https://discovery-fixture-enabled.example/news/one", "one"),
                                candidate("https://discovery-fixture-enabled.example/news/one", "one"),
                                candidate("https://discovery-fixture-enabled.example/news/two", "two"),
                                candidate("https://news.discovery-fixture-enabled.example/three", "three"),
                                candidate("https://approved-cdn.example/four", "four"),
                                candidate("https://outside.example/five", "five")),
                        "errors", List.of("一个条目缺少地址")));
    }

    @Test
    void enabledWhitelistSourceReturnsBoundedDeduplicatedCandidatesWithoutPublishing() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM published_item", Integer.class);
        mvc.perform(post("/api/source-registries/{id}/discover", enabledId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"RSS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(4))
                .andExpect(jsonPath("$.data.filtered_external_count").value(1))
                .andExpect(jsonPath("$.data.filtered_external_domains[0]").value("outside.example"))
                .andExpect(jsonPath("$.data.candidates[0].source_id").value(enabledId))
                .andExpect(jsonPath("$.data.candidates[0].canonical_url")
                        .value("https://discovery-fixture-enabled.example/news/one"))
                .andExpect(jsonPath("$.data.errors[0]").value("一个条目缺少地址"));
        verify(aiClient).discoverArticles(enabledId, "https://discovery-fixture-enabled.example",
                "https://discovery-fixture-enabled.example/rss.xml", "RSS", 2);
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM published_item", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(before, after);
    }

    @Test
    void disabledSourceExternalEntryAndOrganizationAdminAreRejectedBeforeDiscovery() throws Exception {
        mvc.perform(post("/api/source-registries/{id}/discover", disabledId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/source-registries/{id}/discover", enabledId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"RSS\",\"entryUrl\":\"https://outside.example/rss.xml\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/source-registries/{id}/discover", enabledId)
                        .header("Authorization", "Bearer " + login("org_admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"RSS\"}"))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> candidate(String url, String key) {
        OffsetDateTime published = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        return Map.ofEntries(
                Map.entry("source_id", enabledId), Map.entry("discovered_url", url),
                Map.entry("canonical_url", url), Map.entry("title", "离线文章" + key),
                Map.entry("published_time", published.toString()), Map.entry("discovery_method", "RSS"),
                Map.entry("discovery_page", "https://discovery-fixture-enabled.example/rss.xml"),
                Map.entry("content_kind_candidate", "UNKNOWN"), Map.entry("discovered_at", published.plusMinutes(1).toString()),
                Map.entry("dedup_key", key));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
