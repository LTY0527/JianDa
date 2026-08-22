package cn.jianda.publicapi;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResidentCommunityController {
    private static final String DACHANG_REGION = "310113102";
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public ResidentCommunityController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/api/public/resident/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT * FROM resident_user WHERE username=? AND status='ACTIVE'", clean(request.username(), 60));
        if (users.isEmpty() || !passwordEncoder.matches(request.password(), String.valueOf(users.get(0).get("password_hash")))) {
            throw new BusinessException(401, "账号或密码不正确");
        }
        Map<String, Object> user = users.get(0);
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        jdbc.update("DELETE FROM resident_session WHERE resident_user_id=? OR expires_at<CURRENT_TIMESTAMP", user.get("id"));
        jdbc.update("INSERT INTO resident_session(resident_user_id,token_hash,expires_at) VALUES (?,?,?)",
                user.get("id"), sha256(token), Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
        return ApiResponse.ok(Map.of("token", token, "profile", profile(user)));
    }

    @GetMapping("/api/public/resident/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader("X-Resident-Token") String token) {
        return ApiResponse.ok(profile(resident(token)));
    }

    @PostMapping("/api/public/resident/logout")
    public ApiResponse<Void> logout(@RequestHeader("X-Resident-Token") String token) {
        jdbc.update("DELETE FROM resident_session WHERE token_hash=?", sha256(token));
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/public/community/posts")
    public ApiResponse<List<Map<String, Object>>> posts(
            @RequestParam(defaultValue = DACHANG_REGION) String regionCode,
            @RequestParam(defaultValue = "最新") String category) {
        if (!DACHANG_REGION.equals(regionCode)) throw new BusinessException(403, "当前地区尚未开放邻里功能");
        String filter = "最新".equals(category) ? "" : "AND p.category=? ";
        String sql = "SELECT p.id,p.category,p.content,p.region_code,p.district,p.street_or_town,p.status,p.is_demo,p.created_at,"
                + "u.nickname,u.avatar,u.is_demo user_is_demo,"
                + "(SELECT COUNT(*) FROM community_post_like l WHERE l.community_post_id=p.id) like_count,"
                + "(SELECT COUNT(*) FROM community_comment c WHERE c.community_post_id=p.id AND c.status='VISIBLE') comment_count "
                + "FROM community_post p JOIN resident_user u ON u.id=p.resident_user_id "
                + "WHERE p.region_code=? AND p.status IN ('VISIBLE','REPORTED') " + filter
                + "ORDER BY p.created_at DESC,p.id DESC LIMIT 100";
        return ApiResponse.ok("最新".equals(category)
                ? jdbc.queryForList(sql, regionCode)
                : jdbc.queryForList(sql, regionCode, category));
    }

    @PostMapping("/api/public/community/posts")
    public ApiResponse<Map<String, Object>> createPost(
            @RequestHeader("X-Resident-Token") String token,
            @RequestBody PostRequest request) {
        Map<String, Object> user = resident(token);
        String content = clean(request.content(), 500);
        if (content.length() < 2) throw new BusinessException(400, "帖子内容至少需要 2 个字");
        String category = request.category() == null ? "最新" : request.category().trim();
        if (!List.of("最新", "互助", "活动").contains(category)) throw new BusinessException(400, "请选择正确的帖子分类");
        jdbc.update("INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,is_demo) "
                        + "VALUES (?,?,?,?,?,?,?)", user.get("id"), category, content, user.get("region_code"),
                user.get("district"), user.get("street_or_town"), user.get("is_demo"));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM community_post WHERE resident_user_id=?", Long.class, user.get("id"));
        recordUsage(user.get("id"), "POST_CREATE");
        return ApiResponse.ok(Map.of("id", id));
    }

    @PostMapping("/api/public/community/posts/{id}/like")
    public ApiResponse<Map<String, Object>> like(
            @PathVariable long id, @RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> user = resident(token);
        visiblePost(id);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM community_post_like WHERE community_post_id=? AND resident_user_id=?",
                Integer.class, id, user.get("id"));
        boolean liked = count == 0;
        if (liked) jdbc.update("INSERT INTO community_post_like(community_post_id,resident_user_id) VALUES (?,?)", id, user.get("id"));
        else jdbc.update("DELETE FROM community_post_like WHERE community_post_id=? AND resident_user_id=?", id, user.get("id"));
        return ApiResponse.ok(Map.of("liked", liked));
    }

    @GetMapping("/api/public/community/posts/{id}/comments")
    public ApiResponse<List<Map<String, Object>>> comments(@PathVariable long id) {
        visiblePost(id);
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT c.id,c.content,c.created_at,u.nickname,u.is_demo user_is_demo FROM community_comment c "
                        + "JOIN resident_user u ON u.id=c.resident_user_id WHERE c.community_post_id=? "
                        + "AND c.status='VISIBLE' ORDER BY c.created_at,c.id", id));
    }

    @PostMapping("/api/public/community/posts/{id}/comments")
    public ApiResponse<Map<String, Object>> comment(
            @PathVariable long id, @RequestHeader("X-Resident-Token") String token,
            @RequestBody CommentRequest request) {
        Map<String, Object> user = resident(token);
        visiblePost(id);
        String content = clean(request.content(), 300);
        if (content.isBlank()) throw new BusinessException(400, "请输入评论内容");
        jdbc.update("INSERT INTO community_comment(community_post_id,resident_user_id,content) VALUES (?,?,?)",
                id, user.get("id"), content);
        return ApiResponse.ok(Map.of("created", true));
    }

    @PostMapping("/api/public/community/posts/{id}/report")
    public ApiResponse<Void> report(
            @PathVariable long id, @RequestHeader("X-Resident-Token") String token,
            @RequestBody ReportRequest request) {
        Map<String, Object> user = resident(token);
        visiblePost(id);
        String reason = clean(request.reason(), 200);
        if (reason.length() < 5) throw new BusinessException(400, "请简要说明举报原因");
        jdbc.update("INSERT INTO community_report(community_post_id,resident_user_id,reason) VALUES (?,?,?)",
                id, user.get("id"), reason);
        jdbc.update("UPDATE community_post SET status='REPORTED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/community-admin/posts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> moderationQueue() {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT p.id,p.content,p.status,p.created_at,u.nickname,"
                        + "(SELECT COUNT(*) FROM community_report r WHERE r.community_post_id=p.id AND r.status='OPEN') report_count "
                        + "FROM community_post p JOIN resident_user u ON u.id=p.resident_user_id "
                        + "WHERE p.status IN ('REPORTED','HIDDEN') ORDER BY p.updated_at DESC,p.id DESC"));
    }

    @PostMapping("/api/community-admin/posts/{id}/status")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<Void> moderate(@PathVariable long id, @RequestBody ModerateRequest request) {
        String status = request.status() == null ? "" : request.status().trim();
        if (!List.of("VISIBLE", "HIDDEN").contains(status)) throw new BusinessException(400, "不支持的治理状态");
        if (jdbc.update("UPDATE community_post SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id) == 0) {
            throw new BusinessException(404, "帖子不存在");
        }
        jdbc.update("UPDATE community_report SET status='RESOLVED' WHERE community_post_id=? AND status='OPEN'", id);
        return ApiResponse.ok(null);
    }

    private Map<String, Object> resident(String token) {
        if (token == null || token.isBlank()) throw new BusinessException(401, "请先登录居民账号");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.* FROM resident_session s JOIN resident_user u ON u.id=s.resident_user_id "
                        + "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'", sha256(token));
        if (rows.isEmpty()) throw new BusinessException(401, "居民登录已过期，请重新登录");
        if (!DACHANG_REGION.equals(rows.get(0).get("region_code"))) throw new BusinessException(403, "当前地区尚未开放邻里功能");
        return rows.get(0);
    }

    private void visiblePost(long id) {
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM community_post WHERE id=? AND status IN ('VISIBLE','REPORTED')",
                Integer.class, id);
        if (count == 0) throw new BusinessException(404, "帖子不存在或已隐藏");
    }

    private void recordUsage(Object userId, String type) {
        jdbc.update("INSERT INTO usage_event(user_id,event_type) VALUES (?,?)", userId, type);
    }

    private static Map<String, Object> profile(Map<String, Object> user) {
        return Map.ofEntries(Map.entry("id", user.get("id")), Map.entry("username", user.get("username")),
                Map.entry("nickname", user.get("nickname")), Map.entry("district", user.get("district")),
                Map.entry("streetOrTown", user.get("street_or_town")), Map.entry("regionCode", user.get("region_code")),
                Map.entry("demo", Boolean.TRUE.equals(user.get("is_demo"))));
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) throw new BusinessException(400, "内容不能超过 " + max + " 个字");
        return text;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record LoginRequest(String username, String password) {}
    public record PostRequest(String category, String content) {}
    public record CommentRequest(String content) {}
    public record ReportRequest(String reason) {}
    public record ModerateRequest(String status) {}
}
