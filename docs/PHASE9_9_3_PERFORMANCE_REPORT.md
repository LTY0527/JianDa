# Phase 9.9.3 性能回归报告

> 验收日期：2026-08-25
> 测试文件：`tests/e2e/real/phase9-9-3-performance.spec.ts`
> 数据：`artifacts/phase9-9-3-final/perf-*.txt`

## 1. 验收口径

- 在真实浏览器 context（Playwright Chromium）内用 `fetch` 测量，最接近用户体感
- 每个公开接口采样 30 次，管理端接口 30 次，支付会话 5 次
- 计算 p50 / p95 / p99 / max
- 门槛：p95 < 1500ms

## 2. 测试结果

### 2.1 H5 首页公开内容列表

```
endpoint: GET /api/public/items?regionCode=310113102（大场镇，与 H5 首页实际调用一致）
N=30  p50=15ms  p95=27ms  p99=28ms  max=28ms  bytes=35927  status=200
```

原始采样：`[9,10,10,10,12,13,13,14,14,14,15,15,15,15,15,15,15,16,17,17,17,17,17,17,17,18,18,18,19,27,28]`

### 2.2 H5 服务目录

```
endpoint: GET /api/public/service-directory?regionCode=310113
N=30  p95=10ms  status=200
```

### 2.3 管理端文档列表

```
endpoint: GET /api/documents（带管理员 Bearer token）
N=30  p95=22ms  status=200
```

### 2.4 支付会话创建

```
endpoint: POST /api/public/membership/payments（planId=1, method=ALIPAY, 带居民 token）
N=5  p95=26ms  status=200  sessionId=807fc46e-...（真实 UUID）
```

## 3. 门槛核对

| 指标 | 门槛 | 实测 p95 | 状态 |
|---|---|---|---|
| 普通首页 API p95 | < 1.5s | 27ms | PASS |
| 普通内容列表 p95 | < 1.5s | 22ms | PASS |
| 支付 session 创建正常 | 不超时 | 26ms | PASS |

## 4. 其他性能确认

- processing snapshot 不高频返回正文：已有优化（`36afee4 perf(处理): 减少轮询并增加任务心跳保护`）未推翻
- crawl 与 document processing 不互相堵死：AI consumer 批处理 + 心跳保护，未回归
- Web Search / DeepSeek 较慢可接受，UI 有"正在搜索权威来源 / 正在整理答案"占位（AssistantView.vue:270 `.assistant-thinking`）

## 5. 备注

- `regionCode=310113102` 为大场镇真实街镇码，H5 首页 `HomeView.vue:130` 实际使用此值（非区级码 310113，后者被 `PublishedRegionScope` 拒绝以避免跨区内容混入）
- 支付会话测试先 `GET /api/public/membership/plans` 取真实 planId，再 `POST` 用 `{planId, method}`，与前端 `createPaymentSession` 一致

## 6. Final Gate

```
PERFORMANCE_REGRESSION_ACCEPTANCE = PASS
```
