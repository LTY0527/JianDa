package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

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
    void residentRegistrationMediaAndCorsAreReal() throws Exception {
        mvc.perform(get("/api/public/resident/registration-capabilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sms.enabled").value(false))
                .andExpect(jsonPath("$.data.sms.message").value("短信注册尚未启用，请使用用户名和密码注册"));
        String registered = mvc.perform(post("/api/public/resident/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"resident_new\",\"password\":\"Secure123\",\"nickname\":\"新居民\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(registered).path("data").path("token").asText();
        assertTrue(jdbc.queryForObject("SELECT password_hash FROM resident_user WHERE username='resident_new'", String.class).startsWith("$2"));
        mvc.perform(post("/api/public/resident/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"resident_new\",\"password\":\"Secure123\",\"nickname\":\"重复居民\"}"))
                .andExpect(status().isConflict());

        BufferedImage image = new BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "jpg", bytes);
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray());
        String uploaded = mvc.perform(multipart("/api/public/community/media").file(file).header("X-Resident-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.width").value(32)).andReturn().getResponse().getContentAsString();
        long mediaId = objectMapper.readTree(uploaded).path("data").path("id").asLong();
        String postBody = objectMapper.writeValueAsString(Map.of("category", "互助", "content", "带真实图片的邻里帖子", "mediaIds", new long[]{mediaId}));
        String created = mvc.perform(post("/api/public/community/posts").header("X-Resident-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(postBody)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long postId = objectMapper.readTree(created).path("data").path("id").asLong();
        mvc.perform(get("/api/public/community/posts")).andExpect(jsonPath("$.data[?(@.id == " + postId + ")].media[0].id").exists());
        mvc.perform(get("/api/public/community/media/{id}/thumbnail", mediaId)).andExpect(status().isOk());
        String admin = staffLogin();
        mvc.perform(post("/api/community-admin/posts/{id}/status", postId).header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/public/community/media/{id}", mediaId)).andExpect(status().isNotFound());

        mvc.perform(options("/api/public/resident/me").header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Resident-Token"))
                .andExpect(status().isOk()).andExpect(result -> assertTrue(result.getResponse().getHeader("Access-Control-Allow-Headers").contains("X-Resident-Token")));
    }

    @Test
    void unopenedRegionIsRejectedByBackend() throws Exception {
        mvc.perform(get("/api/public/community/posts").param("regionCode", "310113101"))
                .andExpect(status().isForbidden());
    }

    @Test
    void gucunAndMiaohangRegistrationAndFeedsUseRequestedRegion() throws Exception {
        String gucun = mvc.perform(post("/api/public/resident/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"resident_gucun\",\"password\":\"Secure123\",\"nickname\":\"顾村居民\",\"regionCode\":\"310113109\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.regionCode").value("310113109"))
                .andExpect(jsonPath("$.data.profile.streetOrTown").value("顾村镇"))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(gucun).path("data").path("token").asText();
        String created = mvc.perform(post("/api/public/community/posts")
                        .header("X-Resident-Token", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"活动\",\"content\":\"顾村居民真实测试帖子\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long postId = objectMapper.readTree(created).path("data").path("id").asLong();
        mvc.perform(get("/api/public/community/posts").param("regionCode", "310113109"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == " + postId + ")]").exists());
        mvc.perform(get("/api/public/community/posts").param("regionCode", "310113112"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == " + postId + ")]").isEmpty());
    }

    private String staffLogin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "platform_admin", "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
