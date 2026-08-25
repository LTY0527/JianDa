package cn.jianda.commercial;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/membership")
public class MembershipController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MembershipPaymentService payments;

    public MembershipController(JdbcTemplate jdbc, ObjectMapper objectMapper,
            MembershipPaymentService payments) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.payments = payments;
    }

    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,plan_code,name,billing_period,duration_days,price_cents,original_price_cents,"
                        + "benefits_json,demo_price,sort_order FROM membership_plan WHERE enabled=TRUE ORDER BY sort_order,id");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            try {
                item.put("benefits", objectMapper.readValue(String.valueOf(item.remove("benefits_json")),
                        new TypeReference<List<String>>() {}));
            } catch (JsonProcessingException ignored) {
                item.put("benefits", List.of());
            }
            result.add(item);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        return ApiResponse.ok(payments.capabilities());
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> resident = resident(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.status,m.starts_at,m.expires_at,p.name plan_name,p.billing_period "
                        + "FROM resident_membership m JOIN membership_plan p ON p.id=m.plan_id "
                        + "WHERE m.resident_user_id=? AND m.status IN ('ACTIVE','DEMO_ACTIVE_MEMBERSHIP') "
                        + "AND m.expires_at>CURRENT_TIMESTAMP ORDER BY m.expires_at DESC LIMIT 1", resident.get("id"));
        return ApiResponse.ok(rows.isEmpty() ? Map.of("active", false) : new LinkedHashMap<>(rows.get(0)) {{ put("active", true); }});
    }

    @PostMapping("/payments")
    public ApiResponse<Map<String, Object>> createPaymentSession(
            @RequestHeader("X-Resident-Token") String token, @RequestBody PaymentRequest request) {
        Map<String, Object> resident = resident(token);
        return ApiResponse.ok(payments.create(((Number) resident.get("id")).longValue(), request.planId(), request.method()));
    }

    @GetMapping("/payments/{id}")
    public ApiResponse<Map<String, Object>> getPaymentStatus(
            @PathVariable String id, @RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> resident = resident(token);
        return ApiResponse.ok(payments.session(id, ((Number) resident.get("id")).longValue()));
    }

    @PostMapping("/payments/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelPayment(
            @PathVariable String id, @RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> resident = resident(token);
        return ApiResponse.ok(payments.cancel(id, ((Number) resident.get("id")).longValue()));
    }

    private Map<String, Object> resident(String token) {
        if (token == null || token.isBlank()) throw new BusinessException(401, "请先登录居民账号");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.* FROM resident_session s JOIN resident_user u ON u.id=s.resident_user_id "
                        + "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'", sha256(token));
        if (rows.isEmpty()) throw new BusinessException(401, "居民登录已过期，请重新登录");
        return rows.get(0);
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record PaymentRequest(Long planId, String method) {}
}
