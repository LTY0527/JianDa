# Phase 9.9.3 支付 UX 真实验收报告

> 验收日期：2026-08-25
> 测试文件：`tests/e2e/real/phase9-9-3-payment.spec.ts`
> 截图：`artifacts/phase9-9-3-final/h5-payment-*.png`、`h5-membership-plans-390.png`

## 1. 验收范围

Final Regression，不重写支付架构。确认支付宝/微信两条本地测试链路 + 品牌图标 + QR 渲染 + 会员生效均 PASS。

## 2. 真实链路

```
居民注册（真实账号）
→ /membership 选择套餐（周/月/年卡）
→ 选择支付宝 / 微信支付
→ POST /api/public/membership/payments（真实 PaymentSession）
→ QR canvas 真正渲染
→ PENDING
→ internal local-test confirm
→ 前端轮询
→ SUCCESS
→ membership active
```

## 3. 验收点与结果

### 3.1 支付宝链路（ALIPAY）

| 验收点 | 期望 | 实测 | 状态 |
|---|---|---|---|
| 套餐页渲染 | `.membership-plans article` 可见 | 可见 | PASS |
| 测试环境标记 | `.test-environment` = "测试环境" | "测试环境" | PASS |
| 品牌图标 | 支付宝品牌图标存在 | 存在 | PASS |
| PaymentSession 创建 | `POST /payments` 200 + status=PENDING + method=ALIPAY | 200 PENDING ALIPAY | PASS |
| QR Payload | `String(qrPayload).length > 0` | > 0 | PASS |
| QR canvas | `.qr-sheet canvas` 可见 | 可见 | PASS |
| 内部确认 | `POST /internal/test/payments/{id}/confirm` 200 + status=SUCCESS | 200 SUCCESS | PASS |
| 会员生效 | `.membership-active` 可见 + `.payment-status` 含"支付成功" | 可见 | PASS |

### 3.2 微信支付链路（WECHAT）

同支付宝全部验收点，method=WECHAT，品牌图标为微信支付，全部 PASS。

### 3.3 生产环境安全

- `PAYMENT_PROVIDER=local_test`（开发环境）
- 生产 profile 不注册 `LocalTestPaymentProvider`
- 普通用户界面无"演示二维码 / 模拟支付成功 / Demo Payment"
- 仅开发环境角落出现"测试环境"标记

### 3.4 性能（P0-H 补充）

| 指标 | 期望 | 实测 |
|---|---|---|
| 支付会话创建 p95 | < 1.5s | 26ms（5 次采样） |
| sessionId 真实生成 | 非空 | `bf2f7ca0-...`（UUID） |

## 4. 截图证据

- `h5-membership-plans-390.png`：套餐选择
- `h5-payment-alipay-select-390.png`：支付宝选择
- `h5-payment-alipay-qr-390.png`：支付宝 QR
- `h5-payment-wechat-qr-390.png`：微信 QR
- `h5-payment-success-390.png`：支付成功 + membership active

## 5. Final Gate

```
PAYMENT_BRAND_UI_ACCEPTANCE = PASS
PAYMENT_QR_SESSION_ACCEPTANCE = PASS
PAYMENT_LOCAL_TEST_E2E_ACCEPTANCE = PASS
```
