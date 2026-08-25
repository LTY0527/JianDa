package cn.jianda.commercial;

import cn.jianda.common.BusinessException;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${jianda.payment.provider:disabled}' == 'local_test' "
        + "&& ${jianda.payment.local-test-enabled:false}")
public class LocalTestPaymentProvider implements PaymentProvider {
    @Override
    public Map<String, Object> capabilities() {
        return Map.of("available", true, "provider", "LOCAL_TEST", "testEnvironment", true,
                "methods", new String[] {"ALIPAY", "WECHAT"},
                "message", "测试环境支付链路已就绪");
    }

    @Override
    public Map<String, Object> createMembershipSession(String sessionId, String method,
            long amountCents, String planName, Timestamp expiresAt) {
        return Map.of("provider", "LOCAL_TEST", "status", "PENDING",
                "qrPayload", "jianda-local-payment://session/" + sessionId);
    }

    @Override public boolean localTestProvider() { return true; }
    @Override public Map<String, Object> createPayment(long serviceOrderId) { throw unsupported(); }
    @Override public Map<String, Object> queryPayment(long paymentOrderId) { throw unsupported(); }
    @Override public void closePayment(long paymentOrderId) { throw unsupported(); }
    @Override public Map<String, Object> refund(long paymentOrderId, long amountCents) { throw unsupported(); }
    @Override public Map<String, Object> queryRefund(long refundRequestId) { throw unsupported(); }

    private BusinessException unsupported() {
        return new BusinessException(503, "测试 Provider 当前仅用于会员支付验收");
    }
}
