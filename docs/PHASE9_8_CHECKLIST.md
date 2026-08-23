# Phase 9.8 核心可靠性清单

状态只使用 `DONE / PARTIAL / FAILED / BLOCKED / SKIPPED`。

| 顺序 | 任务 | 状态 |
| ---: | --- | --- |
| 1 | 基线审计与分支隔离 | DONE |
| 2 | Rewrite Prompt/Schema 对齐、安全归一化、精准重试和确定性降级 | PARTIAL |
| 3 | PDF 每页质量评分与 OCR 路由 | DONE |
| 4 | PNG/JPG/JPEG 真实 OCR 和追溯 | PARTIAL |
| 5 | 8 份真实 PDF/图片批测 | BLOCKED |
| 6 | 采集错误分类、重试、后台进度和备用路径 | PARTIAL |
| 7 | 大场镇调度、10+ 高价值内容 | PARTIAL |
| 8 | 全部登记官方来源图片候选/缓存开关与历史回填 | FAILED |
| 9 | “立即检查”进度、结果、失败和下一步引导 | PARTIAL |
| 10 | 助手 External 状态与邻里意图检索 | PARTIAL |
| 11 | 办事卡片 Markdown/长文和视觉收口 | PARTIAL |
| 12 | 居民独立登录注册、SmsProvider 抽象与图文帖子 | PARTIAL |
| 13 | REAL E2E、截图、数字报告和最终门禁 | FAILED |

## 2026-08-23 续跑证据

- 任务 3：Docker/Tesseract 对 6 份真实 PDF 完成逐页提取；72 页扫描手册采用 OCR 65 页，POOR 页不再输出垃圾文本；87 项 AI 回归通过。详见 `PHASE9_8_PDF_RELIABILITY_REPORT.md`。
- 任务 4：代码和回归已覆盖 PNG/JPG/JPEG；但真实 JPG 官方端点返回 502、真实原始 PNG 通知缺失，因此只能为 PARTIAL。
- 任务 5：已真实执行 7 个现有文件（含 1 个 PNG 安全失败边界），但文本+扫描混合 PDF、可下载 JPG 通知、PNG 原始通知三个类别 BLOCKED；未用模拟文件、页面截图或封面缓存补齐。
- 任务 6：重试任务现可恢复消费、遵守来源级/全局上限和到期时间，导入失败保持 IMPORT 阶段，未知异常不再伪装为读取超时；真实六来源/Scheduler 总验收尚未完成。
- 任务 10–12：生产实现和自动回归已补齐，Java 聚焦 38 项全绿、H5 typecheck/build 通过、相关 Playwright 6 项通过；最终真实 External/账号/图文闭环尚未完成，因此保持 PARTIAL。

## 2026-08-24 采集、调度与图片复核

- Docker 四项服务 healthy，Flyway V30 已应用；未删除 volume。
- MySQL 仅有 5 个已登记来源，不能满足至少 6 来源门禁；未为凑数新增来源。来源 5 有 Job 28、29 两次真实 `SCHEDULED` 成功证据，其中 Job 29 发现/新增 3/3，`scheduler_identity=jianda-crawl-scheduler-v1`。
- 当前重部署环境 `CRAWL_SCHEDULER_ENABLED=false`；来源 5 的 `next_run_at` 已过期但启动后没有新调度任务。历史路径可用但当前自动触发未通过，因此任务 7 从 FAILED 调整为 PARTIAL，不标 DONE。
- 当前大场/宝山相关 WEB_ARTICLE 为 4，已发布 1；官方门户存在更多真实居民价值候选，但未未经确认批量保存、调用 AI 或发布，10+ 目标仍未达到。
- WEB_ARTICLE 共 31，真实文章图/自定义封面 1，默认图或待补齐 30；缓存成功 1，候选 PENDING 1、APPROVED 1，覆盖率约 3.23%，任务 8 保持 FAILED。
- 详细证据见 `PHASE9_8_CRAWL_RELIABILITY_REPORT.md` 与 `PHASE9_8_IMAGE_BACKFILL_REPORT.md`。
