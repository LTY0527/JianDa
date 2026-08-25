# Phase 9.9 商业化基础设计报告

日期：2026-08-24

## 已实现模型

- B 端：`organization_plan`、`organization_subscription`、`organization_seat`。
- 可信服务：`service_provider`、`service_product`，服务商必须 `VERIFIED + ACTIVE`，服务产品按 `region_code` 隔离。
- 合作展示：`sponsor_campaign`，公开接口仅返回当前有效期内最多一个 ACTIVE 项，并始终携带合作标签。
- 订单退款：`service_order`、`refund_request`，支持待支付订单、取消和已支付订单退款申请边界。
- 支付审计：`payment_order`、`payment_event`，为后续真实 Provider 回调、签名校验和幂等处理保留记录。

## 产品边界

- 不提供理财、贷款、保健品或药品营销能力。
- 政府公开内容、居民发布和合作服务使用不同入口与标签。
- H5 只展示真实数据库中已核验且当前地区可服务的条目；数据库为空时显示真实空状态。
- 机构端商业运营页只读取真实统计，不包含静态随机数字。
- 未写入演示服务商、演示订单、赞助内容或市场价格。

## 自动验证

- `CommercialIntegrationTest`：1/1 通过，覆盖地区隔离、已核验服务、待支付订单、取消、退款边界和支付未配置状态。
- H5 与机构端 typecheck：通过。
