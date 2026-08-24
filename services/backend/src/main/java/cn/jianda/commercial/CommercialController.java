package cn.jianda.commercial;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CommercialController {
    private final JdbcTemplate jdbc;
    private final PaymentProvider paymentProvider;

    public CommercialController(JdbcTemplate jdbc, PaymentProvider paymentProvider) {
        this.jdbc = jdbc;
        this.paymentProvider = paymentProvider;
    }

    @GetMapping("/public/commercial/services")
    public ApiResponse<List<Map<String, Object>>> services(@RequestParam String regionCode) {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT p.id,p.name,p.category,p.description,p.region_code,p.service_area,p.price_cents,"
                        + "v.id provider_id,v.name provider_name,v.verification_status,v.contact_phone,v.refund_policy "
                        + "FROM service_product p JOIN service_provider v ON v.id=p.provider_id "
                        + "WHERE p.status='ACTIVE' AND v.status='ACTIVE' AND v.verification_status='VERIFIED' AND p.region_code=? "
                        + "ORDER BY p.id DESC", regionCode.trim()));
    }

    @GetMapping("/public/commercial/sponsors")
    public ApiResponse<List<Map<String, Object>>> sponsors(@RequestParam String regionCode) {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT id,sponsor_name,title,description,image_url,target_url,label,region_code,start_at,end_at "
                        + "FROM sponsor_campaign WHERE status='ACTIVE' AND (region_code=? OR region_code IS NULL) "
                        + "AND (start_at IS NULL OR start_at<=CURRENT_TIMESTAMP) AND (end_at IS NULL OR end_at>CURRENT_TIMESTAMP) "
                        + "ORDER BY CASE WHEN region_code=? THEN 0 ELSE 1 END,id DESC LIMIT 1", regionCode.trim(), regionCode.trim()));
    }

    @GetMapping("/public/commercial/payment-capabilities")
    public ApiResponse<Map<String, Object>> paymentCapabilities() {
        return ApiResponse.ok(paymentProvider.capabilities());
    }

    @PostMapping("/public/commercial/orders")
    @Transactional
    public ApiResponse<Map<String, Object>> createOrder(@RequestHeader("X-Resident-Token") String token,
                                                        @RequestBody CreateOrderRequest request) {
        Map<String, Object> resident = resident(token);
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > 20) throw new BusinessException(400, "服务数量需在 1-20 之间");
        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT p.*,v.status provider_status,v.verification_status FROM service_product p "
                        + "JOIN service_provider v ON v.id=p.provider_id WHERE p.id=?", request.productId());
        if (products.isEmpty()) throw new BusinessException(404, "服务不存在");
        Map<String, Object> product = products.get(0);
        if (!"ACTIVE".equals(product.get("status")) || !"ACTIVE".equals(product.get("provider_status"))
                || !"VERIFIED".equals(product.get("verification_status"))) {
            throw new BusinessException(409, "该服务当前不可预约");
        }
        if (!String.valueOf(resident.get("region_code")).equals(String.valueOf(product.get("region_code")))) {
            throw new BusinessException(403, "该服务不在您的账号地区范围内");
        }
        long amount = ((Number) product.get("price_cents")).longValue() * quantity;
        String orderNo = "JD" + Instant.now().toEpochMilli() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update("INSERT INTO service_order(order_no,resident_user_id,provider_id,product_id,region_code,quantity,amount_cents,status) "
                        + "VALUES (?,?,?,?,?,?,?,'PENDING_PAYMENT')", orderNo, resident.get("id"), product.get("provider_id"),
                product.get("id"), product.get("region_code"), quantity, amount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo); result.put("status", "PENDING_PAYMENT"); result.put("amountCents", amount);
        result.put("payment", paymentProvider.capabilities());
        return ApiResponse.ok(result);
    }

    @GetMapping("/public/commercial/orders")
    public ApiResponse<List<Map<String, Object>>> orders(@RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> resident = resident(token);
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT o.id,o.order_no,o.quantity,o.amount_cents,o.status,o.created_at,o.cancelled_at,o.completed_at,"
                        + "p.name product_name,v.name provider_name FROM service_order o "
                        + "JOIN service_product p ON p.id=o.product_id JOIN service_provider v ON v.id=o.provider_id "
                        + "WHERE o.resident_user_id=? ORDER BY o.created_at DESC,o.id DESC", resident.get("id")));
    }

    @PostMapping("/public/commercial/orders/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, @RequestHeader("X-Resident-Token") String token) {
        Map<String, Object> resident = resident(token);
        int changed = jdbc.update("UPDATE service_order SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND resident_user_id=? AND status='PENDING_PAYMENT'", id, resident.get("id"));
        if (changed == 0) throw new BusinessException(409, "订单不存在或当前状态不能取消");
        return ApiResponse.ok(null);
    }

    @PostMapping("/public/commercial/orders/{id}/refund")
    public ApiResponse<Map<String, Object>> requestRefund(@PathVariable long id,
            @RequestHeader("X-Resident-Token") String token, @RequestBody RefundRequest request) {
        Map<String, Object> resident = resident(token);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM service_order WHERE id=? AND resident_user_id=?",
                id, resident.get("id"));
        if (rows.isEmpty()) throw new BusinessException(404, "订单不存在");
        Map<String, Object> order = rows.get(0);
        if (!List.of("PAID", "CONFIRMED", "SERVING").contains(String.valueOf(order.get("status")))) {
            throw new BusinessException(409, "当前订单没有可退的已支付款项");
        }
        jdbc.update("INSERT INTO refund_request(service_order_id,resident_user_id,reason,amount_cents,status) VALUES (?,?,?,?,'REQUESTED')",
                id, resident.get("id"), clean(request.reason(), 500), order.get("amount_cents"));
        jdbc.update("UPDATE service_order SET status='REFUNDING' WHERE id=?", id);
        return ApiResponse.ok(Map.of("status", "REFUNDING"));
    }

    @GetMapping("/commercial/overview")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plans", count("organization_plan"));
        result.put("activeSubscriptions", countWhere("organization_subscription", "status='ACTIVE'"));
        result.put("membershipPlans", countWhere("membership_plan", "enabled=TRUE"));
        result.put("activeMembers", countWhere("resident_membership",
                "status='DEMO_ACTIVE_MEMBERSHIP' AND expires_at>CURRENT_TIMESTAMP"));
        result.put("newMembersThisMonth", countWhere("resident_membership",
                "YEAR(created_at)=YEAR(CURRENT_TIMESTAMP) AND MONTH(created_at)=MONTH(CURRENT_TIMESTAMP)"));
        result.put("verifiedProviders", countWhere("service_provider",
                "verification_status='VERIFIED' AND status='ACTIVE'"));
        result.put("activeProducts", countWhere("service_product", "status='ACTIVE'"));
        result.put("ordersThisMonth", countWhere("service_order",
                "YEAR(created_at)=YEAR(CURRENT_TIMESTAMP) AND MONTH(created_at)=MONTH(CURRENT_TIMESTAMP)"));
        result.put("pendingRefunds", countWhere("refund_request", "status='REQUESTED'"));
        result.put("activeSponsors", countWhere("sponsor_campaign", "status='ACTIVE'"));
        result.put("payment", paymentProvider.capabilities());
        return ApiResponse.ok(result);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private long countWhere(String table, String where) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class); }
    private Map<String, Object> resident(String token) {
        if (token == null || token.isBlank()) throw new BusinessException(401, "请先登录居民账号");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.* FROM resident_session s JOIN resident_user u ON u.id=s.resident_user_id "
                        + "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'", sha256(token));
        if (rows.isEmpty()) throw new BusinessException(401, "居民登录已过期，请重新登录");
        return rows.get(0);
    }
    private static String clean(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() < 5 || text.length() > max) throw new BusinessException(400, "请填写 5-" + max + " 字的原因");
        return text;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public record CreateOrderRequest(Long productId, Integer quantity) {}
    public record RefundRequest(String reason) {}
}
