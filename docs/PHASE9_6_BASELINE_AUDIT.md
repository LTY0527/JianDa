# Phase 9.6 真实验收基线审计

更新时间：2026-08-23

## Git 基线

- 工作分支：`feat/phase9-6-real-acceptance-v1`
- 基线提交：`0e1c64b docs(阶段): 完成Phase9.5产品化报告与验收证据`
- 基线远端：`origin/feat/phase9-5-commercial-productization-v1`
- 审计时本地与远端：ahead 0 / behind 0
- `.env`：被 `.gitignore` 忽略且未被 Git 跟踪；本阶段不得读取、输出或提交其内容。
- Flyway：V1—V25 均存在。本阶段不得修改已经提交的历史 migration；新增结构只能追加新版本。

## 受保护的现有修改

审计时工作区已有 15 张 `artifacts/phase7-3/*.png` 被修改。这些文件属于用户现有验收证据，Phase 9.6 不覆盖、不移动、不删除、不暂存、不提交。开始和结束验收时均使用 SHA-256 复核。

## Phase 9.5 能力基线

- 两端 Vue、Spring Boot、FastAPI、MySQL 与 Docker Compose 已形成可运行基线。
- Phase 9.5 自动回归记录：AI 97 项、Maven 67 项、Playwright 105 passed / 11 skipped；两端 typecheck/build 通过。
- 来源发现、影子采集、AI 等待审批、图片候选、人工审核、发布、H5、助手、居民互动和运营指标已有基础实现。
- Phase 9.5 未把真实 External DeepSeek、真实官方 PDF、真实网页发布与真实 Docker 浏览器闭环冒充为已完成验收。

## Phase 9.6 验收口径

自动化 fixture/mock 回归与真实验收分开计数。只有真实网络、Docker、MySQL、官方来源、真实文件、External Provider、真实写入和无请求拦截浏览器流程可以计入 `REAL ACCEPTANCE`。

若任一真实门禁未通过，最终结论只能是 `PARTIAL` 或 `BLOCKED`，不得写成 `PASS`。
