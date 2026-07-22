# 任务清单

更新时间：2026-07-22 19:35

## Phase 0 — 已完成

- [x] 检查实际工作区、Git、Node、Java、Maven 与 Python。
- [x] 创建并核验项目简报、架构、API、任务和 UI 指南。
- [x] 当前 VS Code 工作区作为仓库根目录，无 `jianda/` 嵌套目录。

## Phase 1 — 已完成

- [x] 两个 Vue 3 + TypeScript + Vite 应用可类型检查和生产构建。
- [x] Spring Boot 3 / Java 17 后端可测试、打包和 H2 启动。
- [x] FastAPI AI 服务可导入、启动，MockProvider 测试通过。
- [x] 共享包、MySQL Docker Compose、Dockerfile、启动脚本和 README。

## Phase 2 — 已完成核心能力

- [x] BCrypt 演示账号、JWT 登录和 `/auth/me`。
- [x] ORG_ADMIN / REVIEWER / PLATFORM_ADMIN 角色写入 JWT。
- [x] 材料与日志查询按机构隔离，平台管理员允许跨机构。
- [ ] 平台机构管理页面和更细粒度方法级权限规则。

## Phase 3 — 已完成核心闭环

- [x] PDF/PNG/JPG 类型与 20MB 限制、清理文件名、随机路径保存原件。
- [x] 创建处理任务并调用独立 FastAPI MockProvider。
- [x] 稳定生成字段、原文页码/片段、通俗版、步骤卡片、术语和语音稿。
- [x] AI 不可用时任务标记失败并保留已发布内容。
- [ ] OCR 自动识别；当前图片允许使用手工正文/演示正文。

## Phase 4 — 已完成核心闭环

- [x] 原文与结果左右对照、字段修改与确认。
- [x] 未确认字段阻止审核；无审核记录阻止发布。
- [x] 审核、发布、撤回写入操作日志。
- [x] 后端集成测试覆盖上传 → 处理 → 字段确认 → 审核 → 发布 → 公开读取。
- [ ] 机构端已发布列表和操作日志页面改为实时 API（后端接口已具备日志查询）。

## Phase 5 — 部分完成

- [x] H5 首页、分类、详情、18/20/22/24px 字号、语音播放/暂停/停止、原文和收藏。
- [x] 首页、列表、详情和收藏已连接公开 API；新发布内容可按 slug 查看。
- [x] 375px Playwright 冒烟测试通过。
- [ ] 收藏列表改为后端查询；权威资讯详情的独立信息结构进一步完善。

## Phase 6 — 待继续

- [ ] `ContentCollector` 接口与本地 fixture 实现。
- [ ] 公开信息导入页面接入后端并走同一 AI/审核/发布流程。
- [ ] 平台管理员跨机构来源管理和更正流程。

## Phase 7 — 部分完成

- [x] 前端 `npm run typecheck` 与 `npm run build` 通过。
- [x] 后端 `mvn test`（2 条）与 `mvn package` 通过。
- [x] AI `pytest`（3 条）与模块导入通过。
- [x] Playwright Chromium：机构端 1440×900、H5 375×812，共 2 条通过且无控制台 error。
- [ ] 768px 视觉回归、更多错误/空状态与权限状态测试。
- [ ] 机构端路由级代码拆分，降低当前大包体积警告。

## 当前最早未完成任务

Phase 2 的方法级 RBAC 与平台管理员能力仍不完整；按核心业务优先级，下一项实现 Phase 6 的 `ContentCollector` + 公开信息导入真实接口，并复用现有处理、审核和发布流程。