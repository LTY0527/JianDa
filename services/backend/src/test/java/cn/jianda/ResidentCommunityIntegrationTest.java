package cn.jianda;

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
        "spring.datasource.url=jdbc:h2:mem:jianda-resident-community-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ResidentCommunityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void bcryptResidentCompletesPostLikeCommentReportAndAdminModeration() throws Exception {
        String hash = jdbc.queryForObject("SELECT password_hash FROM resident_user WHERE username='demo_chen'", String.class);
        assertTrue(hash != null && hash.startsWith("$2"));
        String login = mvc.perform(post("/api/public/resident/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo_chen\",\"password\":\"Resident@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.demo").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).path("data").path("token").asText();

        String created = mvc.perform(post("/api/public/community/posts")
                        .header("X-Resident-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"互助\",\"content\":\"测试居民发布的邻里互助信息\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long postId = objectMapper.readTree(created).path("data").path("id").asLong();
        mvc.perform(post("/api/public/community/posts/{id}/like", postId)
                        .header("X-Resident-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.liked").value(true));
        mvc.perform(post("/api/public/community/posts/{id}/comments", postId)
                        .header("X-Resident-Token", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我也想了解这个信息\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/community/posts/{id}/comments", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].content").value("我也想了解这个信息"));
        mvc.perform(post("/api/public/community/posts/{id}/report", postId)
                        .header("X-Resident-Token", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"这是自动测试举报原因\"}"))
                .andExpect(status().isOk());

        String staffToken = staffLogin();
        mvc.perform(get("/api/community-admin/posts").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("REPORTED"));
        mvc.perform(post("/api/community-admin/posts/{id}/status", postId)
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/community/posts").param("regionCode", "310113102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + postId + ")]").isEmpty());
    }

    @Test
    void unopenedRegionIsRejectedByBackend() throws Exception {
        mvc.perform(get("/api/public/community/posts").param("regionCode", "310113101"))
                .andExpect(status().isForbidden());
    }

    private String staffLogin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "platform_admin", "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
