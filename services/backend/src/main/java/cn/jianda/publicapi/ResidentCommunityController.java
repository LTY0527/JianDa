package cn.jianda.publicapi;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ResidentCommunityController {
    private static final String DEFAULT_REGION = SupportedRegions.DEFAULT_CODE;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{4,30}");
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final CommunityMediaService mediaService;

    public ResidentCommunityController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                       CommunityMediaService mediaService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mediaService = mediaService;
    }

    @GetMapping("/api/public/resident/registration-capabilities")
    public ApiResponse<Map<String, Object>> registrationCapabilities() {
        return ApiResponse.ok(Map.of("usernamePassword", true,
                "sms", Map.of("enabled", false, "provider", "NONE", "message", ""), "phonePassword", true));
    }

    @PostMapping("/api/public/resident/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        String username = clean(request.username(), 30);
        String phone = clean(request.phone(), 20);
        String password = request.password() == null ? "" : request.password();
        String nickname = clean(request.nickname(), 60);
        boolean hasUsername = !username.isBlank();
        boolean hasPhone = !phone.isBlank();
        if (!hasUsername && !hasPhone) throw new BusinessException(400, "请输入手机号或用户名");
        if (hasPhone && !PHONE.matcher(phone).matches()) throw new BusinessException(400, "手机号格式不正确");
        if (hasUsername && !USERNAME.matcher(username).matches()) throw new BusinessException(400, "用户名需为 4-30 位字母、数字或下划线");
        if (password.length() < 8 || password.length() > 72 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessException(400, "密码需为 8-72 位，且同时包含字母和数字");
        }
        if (nickname.length() < 2) throw new BusinessException(400, "昵称至少需要 2 个字");
        if (hasUsername) {
            Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM resident_user WHERE username=?", Integer.class, username);
            if (duplicate != null && duplicate > 0) throw new BusinessException(409, "该用户名已被注册");
        }
        if (hasPhone) {
            Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM resident_user WHERE phone=?", Integer.class, phone);
            if (duplicate != null && duplicate > 0) throw new BusinessException(409, "该手机号已被注册");
        }
        SupportedRegions.Region region = SupportedRegions.require(request.regionCode());
        String finalUsername = hasUsername ? username : generateInternalUsername(phone);
        if (hasPhone) {
            jdbc.update("INSERT INTO resident_user(username,password_hash,nickname,phone,district,street_or_town,region_code) VALUES (?,?,?,?,?,?,?)",
                    finalUsername, passwordEncoder.encode(password), nickname, phone, region.district(), region.townName(), region.code());
        } else {
            jdbc.update("INSERT INTO resident_user(username,password_hash,nickname,district,street_or_town,region_code) VALUES (?,?,?,?,?,?)",
                    finalUsername, passwordEncoder.encode(password), nickname, region.district(), region.townName(), region.code());
        }
        return login(new LoginRequest(hasPhone ? phone : finalUsername, password));
    }

    private String generateInternalUsername(String phone) {
        String base = phone != null && !phone.isBlank() ? "jd_" + phone : "jd_" + System.currentTimeMillis();
        if (base.length() > 30) base = base.substring(0, 30);
        String candidate = base;
        for (int i = 1; i <= 50; i++) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM resident_user WHERE username=?", Integer.class, candidate);
            if (count != null && count == 0) return candidate;
            candidate = (base + "_" + i);
            if (candidate.length() > 30) candidate = candidate.substring(0, 30);
        }
        return candidate;
    }

    @PostMapping(value = "/api/public/community/media", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> uploadMedia(
            @RequestHeader("X-Resident-Token") String token, @RequestPart("file") MultipartFile file) throws java.io.IOException {
        return ApiResponse.ok(mediaService.upload(resident(token), file));
    }

    @GetMapping("/api/public/community/media/{id}")
    public ResponseEntity<Resource> media(@PathVariable long id) {
        CommunityMediaService.MediaFile file = mediaService.load(id, false);
        return ResponseEntity.ok().contentType(file.mediaType()).body(file.resource());
    }

    @GetMapping("/api/public/community/media/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable long id) {
        CommunityMediaService.MediaFile file = mediaService.load(id, true);
        return ResponseEntity.ok().contentType(file.mediaType()).body(file.resource());
    }

    @PostMapping("/api/public/resident/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        String credential = clean(request.username(), 60);
        boolean byPhone = PHONE.matcher(credential).matches();
        String sql = byPhone
                ? "SELECT * FROM resident_user WHERE phone=? AND status='ACTIVE'"
                : "SELECT * FROM resident_user WHERE username=? AND status='ACTIVE'";
        List<Map<String, Object>> users = jdbc.queryForList(sql, credential);
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
            @RequestParam(defaultValue = DEFAULT_REGION) String regionCode,
            @RequestParam(defaultValue = "最新") String category) {
        regionCode = SupportedRegions.require(regionCode).code();
        String filter = "最新".equals(category) ? "" : "AND p.category=? ";
        String sql = "SELECT p.id,p.category,p.content,p.region_code,p.district,p.street_or_town,p.status,p.is_demo,p.created_at,"
                + "u.nickname,u.avatar,u.is_demo user_is_demo,"
                + "(SELECT COUNT(*) FROM community_post_like l WHERE l.community_post_id=p.id) like_count,"
                + "(SELECT COUNT(*) FROM community_comment c WHERE c.community_post_id=p.id AND c.status='VISIBLE') comment_count "
                + "FROM community_post p JOIN resident_user u ON u.id=p.resident_user_id "
                + "WHERE p.region_code=? AND p.status='VISIBLE' " + filter
                + "ORDER BY p.created_at DESC,p.id DESC LIMIT 100";
        List<Map<String, Object>> result = "最新".equals(category)
                ? jdbc.queryForList(sql, regionCode)
                : jdbc.queryForList(sql, regionCode, category);
        return ApiResponse.ok(mediaService.mediaForPosts(result));
    }

    @PostMapping("/api/public/community/posts")
    @Transactional
    public ApiResponse<Map<String, Object>> createPost(
            @RequestHeader("X-Resident-Token") String token,
            @RequestBody PostRequest request) {
        Map<String, Object> user = resident(token);
        String content = clean(request.content(), 500);
        if (content.length() < 2) throw new BusinessException(400, "帖子内容至少需要 2 个字");
        String category = request.category() == null ? "最新" : request.category().trim();
        if (!List.of("最新", "互助", "活动").contains(category)) throw new BusinessException(400, "请选择正确的帖子分类");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,is_demo) "
                            + "VALUES (?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setObject(1, user.get("id"));
            statement.setString(2, category);
            statement.setString(3, content);
            statement.setObject(4, user.get("region_code"));
            statement.setObject(5, user.get("district"));
            statement.setObject(6, user.get("street_or_town"));
            statement.setObject(7, user.get("is_demo"));
            return statement;
        }, keys);
        Number generatedId = keys.getKey();
        if (generatedId == null) throw new IllegalStateException("创建帖子后未返回 ID");
        long id = generatedId.longValue();
        mediaService.bind(((Number) user.get("id")).longValue(), id, request.mediaIds());
        recordUsage(user.get("id"), "POST_CREATE");
        return ApiResponse.ok(Map.of("id", id));
    }

    @PostMapping("/api/public/community/posts/{id}/like")
    public ApiResponse<Map<String, Object>> like(
            @PathVariable long id, @RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> user = resident(token);
        requirePublicVisiblePost(id);
        Integer countValue = jdbc.queryForObject("SELECT COUNT(*) FROM community_post_like WHERE community_post_id=? AND resident_user_id=?",
                Integer.class, id, user.get("id"));
        int count = countValue == null ? 0 : countValue;
        boolean liked = count == 0;
        if (liked) jdbc.update("INSERT INTO community_post_like(community_post_id,resident_user_id) VALUES (?,?)", id, user.get("id"));
        else jdbc.update("DELETE FROM community_post_like WHERE community_post_id=? AND resident_user_id=?", id, user.get("id"));
        return ApiResponse.ok(Map.of("liked", liked));
    }

    @GetMapping("/api/public/community/posts/{id}/comments")
    public ApiResponse<List<Map<String, Object>>> comments(@PathVariable long id) {
        requirePublicVisiblePost(id);
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
        requirePublicVisiblePost(id);
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
        requirePublicVisiblePost(id);
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
        if (!SupportedRegions.contains(String.valueOf(rows.get(0).get("region_code")))) {
            throw new BusinessException(403, "当前地区尚未开放邻里功能");
        }
        return rows.get(0);
    }

    private void requirePublicVisiblePost(long id) {
        Integer countValue = jdbc.queryForObject("SELECT COUNT(*) FROM community_post WHERE id=? AND status='VISIBLE'",
                Integer.class, id);
        int count = countValue == null ? 0 : countValue;
        if (count == 0) throw new BusinessException(404, "帖子不存在或已隐藏，暂不能互动");
    }

    private void recordUsage(Object userId, String type) {
        jdbc.update("INSERT INTO usage_event(user_id,event_type) VALUES (?,?)", userId, type);
    }

    private static Map<String, Object> profile(Map<String, Object> user) {
        var entries = new java.util.ArrayList<Map.Entry<String, Object>>();
        entries.add(Map.entry("id", user.get("id")));
        entries.add(Map.entry("username", user.get("username")));
        entries.add(Map.entry("nickname", user.get("nickname")));
        entries.add(Map.entry("district", user.get("district")));
        entries.add(Map.entry("streetOrTown", user.get("street_or_town")));
        entries.add(Map.entry("regionCode", user.get("region_code")));
        entries.add(Map.entry("demo", Boolean.TRUE.equals(user.get("is_demo"))));
        if (user.get("phone") != null) entries.add(Map.entry("phone", user.get("phone")));
        return Map.ofEntries(entries.toArray(new Map.Entry[0]));
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
    public record RegisterRequest(String username, String phone, String password, String nickname, String regionCode) {}
    public record PostRequest(String category, String content, List<Long> mediaIds) {}
    public record CommentRequest(String content) {}
    public record ReportRequest(String reason) {}
    public record ModerateRequest(String status) {}
}
