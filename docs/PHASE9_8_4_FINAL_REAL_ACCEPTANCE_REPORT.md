# Phase 9.8_4 最终真实验收总报告

日期：2026-08-24
分支：`feat/phase9-8-core-reliability-v1`
起始 HEAD：`182f7cf`（远端已同步）
本轮：本地提交，未 push

## 八项 Final Gate

| Gate | 9.8_3 基线 | 9.8_4 结果 | 依据 |
| --- | --- | --- | --- |
| AI_REWRITE_REAL_ACCEPTANCE | PARTIAL | **PASS** | 非关键 enum 失败走 DETERMINISTIC_FALLBACK→WAITING_REVIEW，retry-rewrite 持久化 fact_checkpoint；真实 External 14/14 WAITING_REVIEW |
| PDF_REAL_ACCEPTANCE | BLOCKED | **PASS** | 文字层 PDF / 扫描 PDF / 复杂多页 PDF / 真实 JPG / 真实 PNG 五类全链路 OCR→DeepSeek→WAITING_REVIEW |
| CRAWL_REAL_ACCEPTANCE | PARTIAL | **PASS** | `CRAWL_SCHEDULER_ENABLED=true`，source 5 产生真实 SCHEDULED Job，`scheduler_identity=jianda-crawl-scheduler-v1` |
| IMAGE_SOURCE_PIPELINE_REAL_ACCEPTANCE | FAILED | **PASS** | 静态 og:image / twitter:image / JSON-LD / srcset / data-* 全路径；24 候选 APPROVED；失败原因细分 |
| HOME_VISUAL_ACCEPTANCE | — | **PASS** | Hero 优先真实图、无图文字卡；375/390/768/1440 截图齐全；无横向溢出 |
| ASSISTANT_REAL_ACCEPTANCE | BLOCKED | **PASS** | External DeepSeek smoke 96 字 mode=retrieval citations=3；全局/居民/游客三级预算生效 |
| ADMIN_FLOW_REAL_ACCEPTANCE | BLOCKED | **PASS** | 真实机构账号 UI 全链路登录→采集→处理→审核→发布→H5 验证；11 张截图 |
| RESIDENT_REAL_ACCEPTANCE | PARTIAL | **PASS** | 两真实居民 A/B + 平台管理员 13 步治理回归全通过（举报→Feed 隐藏→互动 404→恢复→可互动→隐藏→再 404） |

## 真实数字（2026-08-24 14:25 复核）

- Docker 服务：4 healthy（mysql 37h / ai-service 1h / backend 30min / frontend 12min）。
- 前端：H5 `:80` 200；机构端 `:8090` 200；后端 `:8080` 200；AI `:8001` 200。
- WEB_ARTICLE：35（PUBLISHED 6 / WAITING_REVIEW 16 / UPLOADED 12 / FAILED 1）。
- WEB_ARTICLE 真实封面（ARTICLE_IMAGE 已缓存）：4/35 = 11.4%（OBSERVED_METRIC）。
- image_candidate：24 条 APPROVED，discovery_method=ARTICLE_IMAGE。
- 大场/宝山相关 WEB_ARTICLE：7（标题/正文/区域标签任一命中；其中 2 篇 PUBLISHED）。
- source_registry：5 来源，source 5 last_status=SUCCESS、next_run_at 已排程。
- community_post：13（VISIBLE 9 / REPORTED 0 / HIDDEN 4）；治理回归帖子 ID 13 终态 HIDDEN（预期）。
- Assistant 历史预算：当日 External smoke 已成功，未触发降级。

## 一键真实验收

- 脚本：`scripts/run_phase9_8_4_real_acceptance.ps1`
- 机器可读：`artifacts/phase9-8-4-final/real-acceptance-summary.json`
- 16 项检查全部 PASS，duration ≈ 3s（健康探测 + 链路复核）。

## 本轮修复清单

1. **AI rewrite 状态边界**：`DocumentService` 在 rewrite 阶段发生非关键 enum/format 错误且 facts 已提取时，走 DETERMINISTIC_FALLBACK 并置 WAITING_REVIEW；新增 `serializeCheckpointForRetry` 持久化 fact_checkpoint，使 retry-rewrite 可恢复；补齐 provider/model/request_id/fingerprint 与 crossedProviderBoundary。
2. **图片发现增强**：`web_ingest.py` 支持 twitter:image、`<picture><source srcset>`、data-src/data-original/data-lazy-src/data-echo、JSON-LD image；`_srcset_urls` 正确剥离密度/宽度提示选择最优候选；过滤 logo/favicon/二维码/1x1/极小尺寸/重复 hash。
3. **REPORTED/HIDDEN 互动阻断**：`ResidentCommunityController` 公开点赞/评论/读帖改用 `requirePublicVisiblePost(id)`，仅 `status='VISIBLE'` 可交互；`CommunityMediaService` 公开媒体读取同样要求 `p.status='VISIBLE'`；治理接口仍可访问 REPORTED/HIDDEN。
4. **Assistant 三级预算**：`AssistantService` 拆分 global/resident/guest 六项限制，`withinDailyBudget` 返回细粒度 errorCode；旧 `ASSISTANT_DAILY_*`（无层级前缀）已从 .env.example / docker-compose.yml / application.yml / 代码 / 测试全部清除（全仓检索 0 命中）。
5. **H2/Flyway 兼容**：`V31__assistant_user_budget.sql` 多列 ADD COLUMN 拆为独立 ALTER TABLE，CoreFlowIntegrationTest 与 AssistantExternalIntegrationTest 全绿。

## 测试（REAL 与 Mock 分开报告）

| 测试 | 结果 |
| --- | --- |
| AI pytest 全量 | 118 passed（含 test_web_ingest 图片发现增强） |
| Backend Maven 全量 | 86 passed / 0 fail / 0 err / 0 skip（20 类），Flyway V31 H2 兼容 |
| 机构端 typecheck/build | institution-web `vue-tsc --noEmit` 0 error；Docker :8090 200 |
| H5 typecheck/build | user-h5 `vue-tsc --noEmit` 0 error；Docker :80 200 |
| Playwright 回归 | core 5 pass / 1 skip；phase9-5-community 4 pass |
| Playwright REAL | phase9-8-4-real-acceptance.spec.ts（Admin 全链路 + H5 多视口） |
| Docker health | 4/4 healthy |
| External DeepSeek smoke | answer 96 字，mode=retrieval，citations=3 |

REAL 以真实 Docker / MySQL / External DeepSeek / 真实账号 / 真实浏览器为准；Mock / fixture / page.route 仅用于上述回归用例，不计入 REAL Gate。

## 安全红线

- 未输出、提交或截图任何 Secret；密码均经环境变量 / `process.env` 传入。
- 未绕过 robots、CAPTCHA、登录或访问控制；浏览器渲染仅用于公开文章图片发现，低频单页超时。
- 未 `git push`、未 `git reset --hard`、未 `docker compose down -v`、未删 MySQL volume、未覆盖历史截图。
- 真实材料、来源数、调度状态、图片覆盖与浏览器结果均按实际记录，未用 fixture/静态假响应冲淡结论。

## 结论

Phase 9.8_4 八项 Final Gate 全部 PASS。核心可靠性实现、真实文件处理、真实网页采集、真实图片发现、真实 DeepSeek 应答、真实管理员全链路、真实居民治理闭环均已达成。后续进入最终人工验收与答辩准备，不再扩展 Phase 9.8。
