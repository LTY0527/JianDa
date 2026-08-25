# Phase 9.9.3 真实验收总清单

> 接管方：TRAE（Codex 因使用上限中断后续跑）
> 工作目录：`E:\Code\JIANDA`
> 分支：`feat/phase9-9-3-real-map-payment-web-v1`
> 现场保护：未执行任何 reset/clean/restore/pull，本地工作区为唯一可信现场
> 验收日期：2026-08-25

## 0. 接管原则核对

| 项 | 状态 |
|---|---|
| 进入后只检查不破坏现场 | PASS |
| `.env` 未被 Git 跟踪 | PASS |
| Secret 未进入 commit / 报告 / 截图 | PASS |
| 未 push 任何提交 | PASS |
| 本地 checkpoint 已保存 | PASS（`ff97198 wip(Phase9.9.3): 保存Codex使用上限中断现场`） |

## 1. 已完成能力回归（不重构，仅 Final Regression）

| Gate | 状态 | 证据 |
|---|---|---|
| AMAP_SDK_HTTP_200 | PASS | 真实高德 SDK 节点全部 < 400 |
| AMAP_BAOSHAN_BOUNDARY | PASS | 宝山区行政边界渲染 |
| AMAP_DACHANG_MARKER | PASS | 大场镇 Marker 真实坐标 |
| AMAP_GUCUN_MARKER | PASS | 顾村镇 Marker 真实坐标 |
| AMAP_MIAOHANG_MARKER | PASS | 庙行镇 Marker 真实坐标 |
| AMAP_REGION_SWITCH_REAL | PASS | 点击 Marker 切换地区 |
| PAYMENT_BRAND_UI_ACCEPTANCE | PASS | 支付宝/微信品牌图标存在 |
| PAYMENT_QR_SESSION_ACCEPTANCE | PASS | PaymentSession 真实创建 + QR canvas 渲染 |
| PAYMENT_LOCAL_TEST_E2E_ACCEPTANCE | PASS | 支付宝+微信 PENDING→SUCCESS→membership active |
| CRAWL_IMPORT_E2E_ACCEPTANCE | PASS | 真实官方网页 import job SUCCESS failed=0 |
| CRAWL_IMPORTED_VISIBLE_ACCEPTANCE | PASS | 内容中心可按 importJobId 定位本次导入 |
| ASSISTANT_EXTERNAL_ACCEPTANCE | PASS | DeepSeek external 真实回答 20 问，HTTP 200，无 Mock fallback |
| ASSISTANT_DETAILED_ANSWER_ACCEPTANCE | PASS | RAG 回答 73-253 字 + 1-3 citations |
| CHANNEL_MODEL_ACCEPTANCE | PASS | V38 栏目字段 + AI 分类器定向 4/4 |
| ADMIN_CHANNEL_PUBLISH_ACCEPTANCE | PASS | 机构后台"调整栏目"入口真实渲染 7 栏目 picker |
| REAL_CONTENT_ONLY_ACCEPTANCE | PASS | 全部真实官方来源，无 Mock/fixture/伪造 |
| PERFORMANCE_REGRESSION_ACCEPTANCE | PASS | items p95=27ms / service-directory p95=10ms / admin docs p95=22ms / payment p95=26ms |
| FULL_RENDERED_E2E_ACCEPTANCE | PASS | H5 6 页 + 机构端 4 页真实渲染，无 console error，无横向溢出 |

## 2. 本次 TRAE 接管后真实补充完成

| 任务 | 状态 | 说明 |
|---|---|---|
| P0-A 已发布栏目调整 API | PASS | 后端 `PUT /api/documents/{id}/publication-channel` 已存在；本次补齐机构端前端"调整栏目"入口、重建 frontend 镜像、Playwright 真实验证 doc 76 HEALTH↔ACTIVITY 切换且 invariant 保持 |
| P0-B WAITING_REVIEW 审核发布 | PASS | doc 73（上海市民政局老年送餐服务实施意见→MEALS）、doc 101（普陀银发经济→ACTIVITY）真实审核发布 |
| P0-C 7 频道补齐 | PASS | HEALTH=9 / MEALS=6 / ELDERLY=5 / FRAUD=5 / SERVICES=5 / ACTIVITY=5 / COMMUNITY=5，全部 ≥5，共 40 篇真实已发布 |
| P0-G 全页面渲染验收 | PASS | H5 首页8频道+高频服务、简达助手、长者食堂、活动报名、会员套餐、7频道切换；机构端内容中心6状态Tab、已发布栏目调整、添加内容弹窗、上传材料 |
| P0-H 性能回归 | PASS | 4 项 p95 远低于 1.5s 门槛 |

## 3. 待 Tavily 凭据补完的项（ACTION_REQUIRED）

| Gate | 当前状态 | 阻塞原因 |
|---|---|---|
| ASSISTANT_WEB_SEARCH_ACCEPTANCE | BLOCKED_BY_CREDENTIALS | `WEB_SEARCH_PROVIDER=disabled`，需配置真实 Tavily API Key |
| PUBLISHED_CHANNEL_ADJUSTMENT_ACCEPTANCE | PASS | — |
| CHANNEL_CONTENT_COVERAGE_ACCEPTANCE | PASS | — |
| ASSISTANT_30Q_REAL_ACCEPTANCE | PARTIAL(20/30) | RAG 10 + 社区 5 + 安全 5 已 PASS，联网 10 问待 Tavily 配置后补跑 |

## 4. Final Gate 汇总

```
AMAP_REAL_ACCEPTANCE = PASS
AMAP_REGION_SWITCH_ACCEPTANCE = PASS
PAYMENT_BRAND_UI_ACCEPTANCE = PASS
PAYMENT_QR_SESSION_ACCEPTANCE = PASS
PAYMENT_LOCAL_TEST_E2E_ACCEPTANCE = PASS
ASSISTANT_EXTERNAL_ACCEPTANCE = PASS
ASSISTANT_DETAILED_ANSWER_ACCEPTANCE = PASS
ASSISTANT_30Q_REAL_ACCEPTANCE = PARTIAL（20/30，待 Tavily 补 10 联网问）
ASSISTANT_WEB_SEARCH_ACCEPTANCE = BLOCKED_BY_CREDENTIALS
CRAWL_IMPORT_E2E_ACCEPTANCE = PASS
CRAWL_IMPORTED_VISIBLE_ACCEPTANCE = PASS
CHANNEL_MODEL_ACCEPTANCE = PASS
PUBLISHED_CHANNEL_ADJUSTMENT_ACCEPTANCE = PASS
ADMIN_CHANNEL_PUBLISH_ACCEPTANCE = PASS
CHANNEL_CONTENT_COVERAGE_ACCEPTANCE = PASS
REAL_CONTENT_ONLY_ACCEPTANCE = PASS
PERFORMANCE_REGRESSION_ACCEPTANCE = PASS
FULL_RENDERED_E2E_ACCEPTANCE = PASS
```

除"配置 Tavily API Key 并补跑联网 10 问"这一人工凭据步骤外，其余全部真实 PASS。

## 5. 测试资产

- Playwright 真实测试：6 个 spec，共 18 个用例全 PASS
  - `tests/e2e/real/phase9-9-3-amap.spec.ts`
  - `tests/e2e/real/phase9-9-3-payment.spec.ts`
  - `tests/e2e/real/phase9-9-3-runtime-capabilities.spec.ts`
  - `tests/e2e/real/phase9-9-3-h5-full-render.spec.ts`（本次新增）
  - `tests/e2e/real/phase9-9-3-admin-full-render.spec.ts`（本次新增）
  - `tests/e2e/real/phase9-9-3-performance.spec.ts`（本次新增）
- 截图证据：`artifacts/phase9-9-3-final/*.png`（地图/支付/H5/机构端/栏目调整）
- 性能数据：`artifacts/phase9-9-3-final/perf-*.txt`
- 30 问结果：`artifacts/phase9-9-3-final/assistant_30q_results.json`
