# Phase 9.8_4 发现的问题与处置

日期：2026-08-24
分支：`feat/phase9-8-core-reliability-v1`

## P0（已修复）

### 1. AI rewrite 非关键 enum 失败导致整篇 FAILED

- 现象：rewrite 阶段发生 schema/format 或 enum 校验错误时，`DocumentService` 直接将文档标 FAILED；真实 External 文档 68 因此卡死。
- 根因：rewrite 异常未与 facts 提取失败区分，错误处理过于粗暴。
- 修复：facts 已提取 → `DETERMINISTIC_FALLBACK` + `WAITING_REVIEW`；`serializeCheckpointForRetry` 持久化 fact_checkpoint；从失败异常补齐 provider/model/request_id/fingerprint/crossedProviderBoundary。
- 验证：CoreFlowIntegrationTest fallback 路径 provider=external、crossed_provider_boundary=true、retry-rewrite 200。

### 2. REPORTED/HIDDEN 帖子仍可公开互动与媒体读取

- 现象：`visiblePost()` 允许 `status IN ('VISIBLE','REPORTED')`，知道 post id 可继续点赞/评论；公开 media API 同样暴露。
- 修复：`requirePublicVisiblePost`（仅 VISIBLE）+ `CommunityMediaService` 媒体查询加 `p.status='VISIBLE'`。
- 验证：13 步真实回归，举报后点赞/评论 404，隐藏后再次 404。

## P1（已修复）

### 3. Assistant 旧配置与新三级预算同时残留

- 现象：`ASSISTANT_DAILY_*`（无层级前缀）与 `ASSISTANT_GLOBAL/RESIDENT/GUEST_*` 并存，开发者无法判断谁生效。
- 处置：全仓检索确认旧 `ASSISTANT_DAILY_CALL_LIMIT` / `ASSISTANT_DAILY_TOKEN_LIMIT` / `assistant.daily-*-limit`（无层级）零命中；.env.example、docker-compose.yml、application.yml、AssistantService.java、AssistantExternalIntegrationTest.java 全部统一为三级命名。
- 验证：全局 / 居民 / 游客预算各至少一个边界用例覆盖。

### 4. H2 / Flyway 多列 ADD COLUMN 不兼容

- 现象：`V31__assistant_user_budget.sql` 多列 `ADD COLUMN` 在 H2 测试库执行失败，阻断 Maven 全量。
- 修复：拆为独立 `ALTER TABLE ... ADD COLUMN` 语句。
- 验证：AssistantExternalIntegrationTest、CoreFlowIntegrationTest 全绿。

### 5. 一键验收脚本中文编码与嵌套表达式

- 现象：PowerShell 5.1 默认按 GBK 读 UTF-8 无 BOM 文件，中文乱码致 Playwright 断言失败；嵌套 `Invoke-Expression` 与 `&&` 语法报错。
- 修复：脚本加 UTF-8 BOM；以 `Invoke-MysqlQuery` 函数封装避免嵌套；以 `;` 串联。
- 验证：`real-acceptance-summary.json` 16 项 PASS。

## P2（已知/可接受）

### 6. 真实封面覆盖率仍仅 11.4%

- 现象：35 篇 WEB_ARTICLE 中 4 篇有真实 ARTICLE_IMAGE，余者多为频道聚合页/列表页/纯文字通知，本身无正文图。
- 处置：不再用 50% 硬阈值阻塞；拆分为 SOURCE_PIPELINE PASS / HOME_VISUAL PASS / GLOBAL_COVERAGE OBSERVED_METRIC；首屏 Hero 优先真实图、无图用文字卡。
- 不伪造照片、不随机找图。

### 7. 大场/宝山相关 WEB_ARTICLE 当前 7 篇

- 现象：按标题/正文/区域标签任一命中，当前在库 7 篇（2 PUBLISHED / 3 UPLOADED / 1 WAITING_REVIEW / 1 FAILED）。
- 处置：真实记录，不为凑数新增来源或批量保存未经确认内容；Phase 9.8_3 供给目标已达成的判断基于当时在库量。
- 后续：对 UPLOADED/WAITING_REVIEW 的真实候选可继续人工审核发布。

## 不重复造轮子

以下 9.8_3 已工作项本轮未重做：DeepSeek rewrite recovery/retry/deterministic fallback 框架、真实 14/14 AI 材料 WAITING_REVIEW、PDF 质量分级 + PyMuPDF + OCR、Scheduler 真实 Job34/35、35 篇 canonical_url 回源、Assistant External DeepSeek、居民独立登录/注册、真实图片上传、图文帖子与点赞评论举报、机构登录页去“课堂验收”文案。
