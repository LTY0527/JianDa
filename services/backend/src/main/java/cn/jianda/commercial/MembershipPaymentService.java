package cn.jianda.commercial;

import cn.jianda.common.BusinessException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipPaymentService {
    private final JdbcTemplate jdbc;
    private final PaymentProvider provider;

    public MembershipPaymentService(JdbcTemplate jdbc, PaymentProvider provider) {
        this.jdbc = jdbc;
        this.provider = provider;
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>(provider.capabilities());
        result.put("testEnvironment", provider.localTestProvider());
        result.put("realPaymentAvailable", !provider.localTestProvider() && Boolean.TRUE.equals(result.get("available")));
        return result;
    }

    @Transactional
    public Map<String, Object> create(long residentId, long planId, String method) {
        if (!Boolean.TRUE.equals(provider.capabilities().get("available"))) {
            throw new BusinessException(409, "线上支付暂未开通");
        }
        String paymentMethod = method == null ? "" : method.trim().toUpperCase();
        if (!Set.of("ALIPAY", "WECHAT").contains(paymentMethod)) {
            throw new BusinessException(400, "请选择支付宝或微信支付");
        }
        List<Map<String, Object>> plans = jdbc.queryForList(
                "SELECT * FROM membership_plan WHERE id=? AND enabled=TRUE", planId);
        if (plans.isEmpty()) throw new BusinessException(404, "会员套餐不存在或已下架");
        Map<String, Object> plan = plans.get(0);
        String id = UUID.randomUUID().toString();
        Timestamp expiresAt = Timestamp.valueOf(LocalDateTime.now().plusMinutes(5));
        Map<String, Object> providerResult = provider.createMembershipSession(id, paymentMethod,
                ((Number) plan.get("price_cents")).longValue(), String.valueOf(plan.get("name")), expiresAt);
        String qrPayload = String.valueOf(providerResult.get("qrPayload"));
        jdbc.update("INSERT INTO membership_payment_session(id,resident_user_id,membership_plan_id,provider,"
                        + "payment_method,amount_cents,status,qr_payload,expires_at) VALUES (?,?,?,?,?,?,'PENDING',?,?)",
                id, residentId, plan.get("id"), providerResult.get("provider"), paymentMethod,
                plan.get("price_cents"), qrPayload, expiresAt);
        return session(id, residentId);
    }

    @Transactional
    public Map<String, Object> session(String id, long residentId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT s.id,s.status,s.provider,s.payment_method,s.amount_cents,"
                        + "s.qr_payload,s.expires_at,s.paid_at,p.name "
                        + "FROM membership_payment_session s JOIN membership_plan p ON p.id=s.membership_plan_id "
                        + "WHERE s.id=? AND s.resident_user_id=?",
                (rs, rowNum) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("sessionId", rs.getString("id"));
                    result.put("status", rs.getString("status"));
                    result.put("provider", rs.getString("provider"));
                    result.put("method", rs.getString("payment_method"));
                    result.put("amountCents", rs.getLong("amount_cents"));
                    result.put("qrPayload", rs.getString("qr_payload"));
                    result.put("expiresAt", rs.getTimestamp("expires_at"));
                    result.put("paidAt", rs.getTimestamp("paid_at"));
                    result.put("planName", rs.getString("name"));
                    return result;
                }, id, residentId);
        if (rows.isEmpty()) throw new BusinessException(404, "支付订单不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        java.util.Date expiresAt = (java.util.Date) row.get("expiresAt");
        if ("PENDING".equals(row.get("status"))
                && expiresAt != null && expiresAt.before(new java.util.Date())) {
            jdbc.update("UPDATE membership_payment_session SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            row.put("status", "EXPIRED");
        }
        row.put("testEnvironment", provider.localTestProvider());
        return row;
    }

    @Transactional
    public Map<String, Object> cancel(String id, long residentId) {
        int changed = jdbc.update("UPDATE membership_payment_session SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND resident_user_id=? AND status='PENDING'", id, residentId);
        if (changed == 0) throw new BusinessException(409, "支付订单不存在或当前状态不能取消");
        return session(id, residentId);
    }

    @Transactional
    public Map<String, Object> confirmLocalTest(String id, long residentId) {
        if (!provider.localTestProvider()) throw new BusinessException(404, "测试确认接口未启用");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.*,p.duration_days FROM membership_payment_session s JOIN membership_plan p "
                        + "ON p.id=s.membership_plan_id WHERE s.id=? AND s.resident_user_id=? FOR UPDATE", id, residentId);
        if (rows.isEmpty()) throw new BusinessException(404, "支付订单不存在");
        Map<String, Object> payment = rows.get(0);
        if (!"PENDING".equals(payment.get("status"))
                || ((java.util.Date) payment.get("expires_at")).before(new java.util.Date())) {
            throw new BusinessException(409, "二维码已失效或订单已处理");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime membershipExpires = now.plusDays(((Number) payment.get("duration_days")).longValue());
        jdbc.update("UPDATE membership_payment_session SET status='SUCCESS',paid_at=CURRENT_TIMESTAMP,"
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        jdbc.update("INSERT INTO resident_membership(resident_user_id,plan_id,status,starts_at,expires_at) "
                        + "VALUES (?,?,'ACTIVE',?,?)", residentId, payment.get("membership_plan_id"),
                Timestamp.valueOf(now), Timestamp.valueOf(membershipExpires));
        return session(id, residentId);
    }
}
