package cn.jianda.commercial;

import cn.jianda.common.BusinessException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DisabledPaymentProvider implements PaymentProvider {
    @Override public Map<String, Object> capabilities() {
        return Map.of("available", false, "provider", "UNCONFIGURED",
                "message", "线上支付暂未开通，平台不会伪造支付成功。可联系服务机构确认线下办理方式。");
    }
    @Override public Map<String, Object> createPayment(long serviceOrderId) { throw unavailable(); }
    @Override public Map<String, Object> queryPayment(long paymentOrderId) { return capabilities(); }
    @Override public void closePayment(long paymentOrderId) { throw unavailable(); }
    @Override public Map<String, Object> refund(long paymentOrderId, long amountCents) { throw unavailable(); }
    @Override public Map<String, Object> queryRefund(long refundRequestId) { return capabilities(); }
    private BusinessException unavailable() { return new BusinessException(503, "线上支付暂未开通，未创建支付交易"); }
}
