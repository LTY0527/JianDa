package cn.jianda.commercial;

import java.util.Map;
import java.sql.Timestamp;

public interface PaymentProvider {
    Map<String, Object> capabilities();
    Map<String, Object> createPayment(long serviceOrderId);
    Map<String, Object> queryPayment(long paymentOrderId);
    void closePayment(long paymentOrderId);
    Map<String, Object> refund(long paymentOrderId, long amountCents);
    Map<String, Object> queryRefund(long refundRequestId);
    Map<String, Object> createMembershipSession(String sessionId, String method, long amountCents,
                                                String planName, Timestamp expiresAt);
    default boolean localTestProvider() { return false; }
}
