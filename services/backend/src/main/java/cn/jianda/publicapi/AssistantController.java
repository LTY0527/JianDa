package cn.jianda.publicapi;

import cn.jianda.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/assistant")
public class AssistantController {
    private final AssistantService assistantService;
    private final JdbcTemplate jdbc;

    public AssistantController(AssistantService assistantService, JdbcTemplate jdbc) {
        this.assistantService = assistantService;
        this.jdbc = jdbc;
    }

    @GetMapping("/suggestions")
    public ApiResponse<List<String>> suggestions() {
        return ApiResponse.ok(assistantService.suggestions());
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(assistantService.status());
    }

    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Resident-Token", required = false) String residentToken,
            @RequestHeader(value = "X-Visitor-Id", required = false) String visitorIdHeader) {
        Long residentUserId = resolveResidentUserId(residentToken);
        String visitorId = residentUserId != null ? null : normalizeVisitorId(visitorIdHeader);
        return ApiResponse.ok(assistantService.chat(
                request.message(), request.contextSlug(), request.regionCode(),
                residentUserId, visitorId));
    }

    private Long resolveResidentUserId(String token) {
        if (token == null || token.isBlank()) return null;
        String hash = sha256(token);
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM resident_session s JOIN resident_user u ON u.id=s.resident_user_id "
                        + "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'",
                Long.class, hash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String normalizeVisitorId(String visitorId) {
        String raw = visitorId == null ? "" : visitorId.trim();
        if (raw.length() >= 8 && raw.length() <= 64) return raw;
        return "anon-" + (raw.isBlank() ? "default" : sha256(raw).substring(0, 16));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record ChatRequest(
            @NotBlank(message = "请输入问题")
            @Size(max = 500, message = "问题不能超过500个字符")
            String message,
            String contextSlug,
            String regionCode) {}
}
