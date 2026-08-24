package cn.jianda.commercial;

import java.util.Map;

public interface PaymentProvider {
    Map<String, Object> capabilities();
    Map<String, Object> createPayment(long serviceOrderId);
    Map<String, Object> queryPayment(long paymentOrderId);
    void closePayment(long paymentOrderId);
    Map<String, Object> refund(long paymentOrderId, long amountCents);
    Map<String, Object> queryRefund(long refundRequestId);
}
