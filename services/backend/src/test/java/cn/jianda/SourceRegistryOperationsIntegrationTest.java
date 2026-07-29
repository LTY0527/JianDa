package cn.jianda;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.collector.SourceRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private String platformAuth;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM source_registry WHERE domain LIKE 'phase93b-%'");
        platformAuth = "Bearer " + login("platform_admin");
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
