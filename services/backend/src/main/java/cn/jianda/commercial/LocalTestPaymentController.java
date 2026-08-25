package cn.jianda.commercial;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/test/payments")
@ConditionalOnExpression("'${jianda.payment.provider:disabled}' == 'local_test' "
        + "&& ${jianda.payment.local-test-enabled:false}")
public class LocalTestPaymentController {
    private final JdbcTemplate jdbc;
    private final MembershipPaymentService payments;

    public LocalTestPaymentController(JdbcTemplate jdbc, MembershipPaymentService payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @PathVariable String id, @RequestHeader("X-Resident-Token") String token) {
        List<Long> ids = jdbc.query("SELECT u.id FROM resident_session s JOIN resident_user u "
                        + "ON u.id=s.resident_user_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP "
                        + "AND u.status='ACTIVE'", (row, index) -> row.getLong(1), sha256(token));
        if (ids.isEmpty()) throw new BusinessException(401, "居民登录已过期，请重新登录");
        return ApiResponse.ok(payments.confirmLocalTest(id, ids.get(0)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
