# Phase 9.9.2 会员与支付报告

H5 新增 `/membership`，展示数据库配置的周卡、月卡、年卡。核心公共服务永久免费，会员仅为可选增值权益，不包含医疗、优先就医等未实现承诺。

课堂 Demo 支付流程支持支付宝/微信选择、确认 Bottom Sheet 和真实二维码渲染。二维码只编码 `jianda-demo-payment://session/{id}`，页面明确“演示支付、不会扣款”。状态仅为 `DEMO_PENDING / DEMO_CONFIRMED / DEMO_ACTIVE_MEMBERSHIP`，没有写入 `PAID`。

真实支付宝/微信商户凭据缺失，`REAL_PAYMENT_PROVIDER_ACCEPTANCE=BLOCKED_BY_CREDENTIALS`。取消支付会同时关闭二维码和套餐确认层。
