# Phase 9.9.3 "加入内容中心"采集根因与修复报告

> 验收日期：2026-08-25

## 1. 历史根因（Codex 已修复）

### 1.1 现象

```
AI 服务实际返回 HTTP 200
但 HttpAiClient.readResponse 无条件截断成功 response body 到约 64KB
导致完整 JSON 被截断
随后统一包装为"网页暂时无法访问或解析"
```

### 1.2 根因

`HttpAiClient.readResponse` 对成功响应（2xx）也执行了与错误响应相同的安全截断，导致：
- 大于 ~64KB 的成功 JSON 被截断
- 截断后的 JSON 无法反序列化
- 上层统一捕获异常并回退为"网页暂时无法访问或解析"

### 1.3 修复

```
错误响应：允许安全截断（保护日志与内存）
成功响应：在合理上限内读取完整正文（不截断 2xx body）
```

### 1.4 修复后真实结果

```
任务 70 = SUCCESS
新增文档 86、87
失败 0
内容中心可通过 importJobId 显示本次 2 篇并高亮
```

## 2. 本次 TRAE 接管后的真实补采集

### 2.1 doc 73 — 上海市民政局老年送餐服务实施意见

- 来源：上海市民政局官方网页
- 频道：MEALS（助餐）
- 流程：URL 导入 → AI 处理 → 字段确认 → 地域范围修正（`localScope=DISTRICT_SHARED`，province=上海市，city=上海市，district=宝山区，regionCode=310113）→ 图片审核（下载封面上传，`image_reviewed=true`）→ 发布
- 证据：`artifacts/phase9-9-3-final/publish_doc73.py`、`verify_doc73.py`

### 2.2 doc 101 — 普陀银发经济升级（含电吹管公益课等活动）

- 来源：上海市政府官方网页
- 频道：ACTIVITY（活动）
- 流程：URL 导入 → AI 处理等待（`poll_doc101.py` 轮询）→ 字段确认 → 图片审核 → 发布
- 证据：`artifacts/phase9-9-3-final/import_activity.py`、`publish_doc101.py`、`poll_doc101.py`

## 3. Final Regression

```
CRAWL_IMPORT_E2E_ACCEPTANCE = PASS
真实上海医保候选 2 篇 → collect-batch → job SUCCESS → failed=0 → source_document 存在 → 内容中心定位本次导入
CRAWL_IMPORTED_VISIBLE_ACCEPTANCE = PASS
```

## 4. 未重新猜的根因

以下历史误判本次未复现，未重新排查：
```
registryFor
sourceId
AI 网站打不开
```

根因始终是 `HttpAiClient.readResponse` 成功响应被截断，已修复且本次新采集（doc 73/101）均 SUCCESS。

## 5. Final Gate

```
CRAWL_IMPORT_E2E_ACCEPTANCE = PASS
CRAWL_IMPORTED_VISIBLE_ACCEPTANCE = PASS
REAL_CONTENT_ONLY_ACCEPTANCE = PASS（全部真实官方来源，无 Mock/fixture/伪造）
```
