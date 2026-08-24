package cn.jianda.commercial;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean demoMode;

    public MembershipController(JdbcTemplate jdbc, ObjectMapper objectMapper,
            @Value("${jianda.payment.demo-mode:false}") boolean demoMode) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.demoMode = demoMode;
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
        return ApiResponse.ok(Map.of("demoMode", demoMode, "realPaymentAvailable", false,
                "message", demoMode ? "演示支付已启用，不会扣款" : "线上支付暂未开通"));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> resident = resident(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.status,m.starts_at,m.expires_at,p.name plan_name,p.billing_period "
                        + "FROM resident_membership m JOIN membership_plan p ON p.id=m.plan_id "
                        + "WHERE m.resident_user_id=? AND m.status='DEMO_ACTIVE_MEMBERSHIP' "
                        + "AND m.expires_at>CURRENT_TIMESTAMP ORDER BY m.expires_at DESC LIMIT 1", resident.get("id"));
        return ApiResponse.ok(rows.isEmpty() ? Map.of("active", false) : new LinkedHashMap<>(rows.get(0)) {{ put("active", true); }});
    }

    @PostMapping("/demo-payments")
    @Transactional
    public ApiResponse<Map<String, Object>> createDemoPayment(
            @RequestHeader("X-Resident-Token") String token, @RequestBody PaymentRequest request) {
        if (!demoMode) throw new BusinessException(409, "线上支付暂未开通");
        if (!Set.of("ALIPAY", "WECHAT").contains(request.method())) throw new BusinessException(400, "请选择支付宝或微信支付");
        Map<String, Object> resident = resident(token);
        List<Map<String, Object>> plans = jdbc.queryForList("SELECT * FROM membership_plan WHERE id=? AND enabled=TRUE", request.planId());
        if (plans.isEmpty()) throw new BusinessException(404, "会员套餐不存在或已下架");
        Map<String, Object> plan = plans.get(0);
        String id = UUID.randomUUID().toString();
        String payload = "jianda-demo-payment://session/" + id;
        Timestamp expires = Timestamp.valueOf(LocalDateTime.now().plusMinutes(5));
        jdbc.update("INSERT INTO demo_payment_session(id,resident_user_id,plan_id,payment_method,amount_cents,qr_payload,status,expires_at) "
                        + "VALUES (?,?,?,?,?,?,'DEMO_PENDING',?)", id, resident.get("id"), plan.get("id"),
                request.method(), plan.get("price_cents"), payload, expires);
        return ApiResponse.ok(Map.of("sessionId", id, "status", "DEMO_PENDING", "method", request.method(),
                "amountCents", plan.get("price_cents"), "planName", plan.get("name"), "qrPayload", payload,
                "expiresAt", expires, "demo", true));
    }

    @PostMapping("/demo-payments/{id}/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> confirmDemoPayment(
            @PathVariable String id, @RequestHeader("X-Resident-Token") String token) {
        if (!demoMode) throw new BusinessException(409, "演示支付未启用");
        Map<String, Object> resident = resident(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.*,p.duration_days FROM demo_payment_session s JOIN membership_plan p ON p.id=s.plan_id "
                        + "WHERE s.id=? AND s.resident_user_id=?", id, resident.get("id"));
        if (rows.isEmpty()) throw new BusinessException(404, "演示支付会话不存在");
        Map<String, Object> session = rows.get(0);
        if (!"DEMO_PENDING".equals(session.get("status")) || ((java.util.Date) session.get("expires_at")).before(new java.util.Date())) {
            throw new BusinessException(409, "演示二维码已失效或已确认");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(((Number) session.get("duration_days")).longValue());
        jdbc.update("UPDATE demo_payment_session SET status='DEMO_CONFIRMED',confirmed_at=CURRENT_TIMESTAMP WHERE id=?", id);
        jdbc.update("INSERT INTO resident_membership(resident_user_id,plan_id,status,starts_at,expires_at) "
                        + "VALUES (?,?,'DEMO_ACTIVE_MEMBERSHIP',?,?)", resident.get("id"), session.get("plan_id"),
                Timestamp.valueOf(now), Timestamp.valueOf(expires));
        return ApiResponse.ok(Map.of("sessionId", id, "paymentStatus", "DEMO_CONFIRMED",
                "membershipStatus", "DEMO_ACTIVE_MEMBERSHIP", "expiresAt", Timestamp.valueOf(expires), "demo", true));
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
