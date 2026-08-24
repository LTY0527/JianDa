# Phase 9.9 支付架构与真实边界

## Provider 接口

`PaymentProvider` 定义 `createPayment`、`queryPayment`、`closePayment`、`refund` 和 `queryRefund`。当前注入 `DisabledPaymentProvider`，因为仓库没有微信/支付宝商户号、证书和公网回调域名。

## 当前可验收能力

- 可以基于真实已核验服务创建 `PENDING_PAYMENT` 订单。
- 可以查询本人订单并取消待支付订单。
- 退款只能对 PAID/CONFIRMED/SERVING 订单创建申请；未支付订单不会伪造退款。
- 支付能力接口明确返回 `available=false` 与 `provider=UNCONFIGURED`。
- 调用未配置 Provider 的支付创建或退款会返回 503，不会生成成功事件。

## Gate

- `ORDER_FLOW_REAL_ACCEPTANCE`：自动测试通过，真实 UI 浏览器验收待完成。
- `PAYMENT_MODEL_REAL_ACCEPTANCE`：PASS。
- `REAL_PAYMENT_PROVIDER_ACCEPTANCE`：`BLOCKED_BY_CREDENTIALS`。

正式接入时必须补齐商户签约、证书/密钥安全存储、HTTPS 回调、签名验证、幂等键、对账和退款通知；密钥不得进入仓库、argv 或日志。
