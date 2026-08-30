# 简达最终报告真实浏览器截图清单

## 真实性与环境

- 截图日期：2026-08-31（Asia/Shanghai）
- 当前分支：`docs/report-final-artifacts-20260831`
- 同步前 HEAD：`7601a88bff85b428acb11a4d6736dde839499780`
- `origin/main` HEAD：`e45478718d741914b03689f3371af66fc481b7be`
- 同步后基线 HEAD：`e45478718d741914b03689f3371af66fc481b7be`
- 同步方式：安全 stash 后 `git pull --ff-only`，再 `git stash apply`
- 冲突：无
- 浏览器：Playwright Chromium `149.0.7827.55`
- Browser 插件：本会话不可用；按前端调试技能要求回退到仓库 Playwright Chromium
- 最终截图 Mock 路由数：**0**；未使用 `page.route()`、静态替身或伪造进度
- 居民账号角色：`demo_chen`，DEMO 居民，大场镇
- 机构账号角色：`platform_admin`，平台管理员
- 敏感信息：未记录密码、API Key、JWT、Cookie、Authorization 或 Bearer 内容

## 报告图

| 图号 | 文件 | 原始页面 | 视口 / 输出尺寸 | 真实数据来源 | External AI | doc_id / slug | 时间 |
|---|---|---|---|---|---|---|---|
| 图5.1 | `figures/图5.1_原始材料与智能处理结果对照审核界面.png` | `http://127.0.0.1:8090/documents/67/review` | 1440×900 | 原 PDF、提取文本、已确认结构化字段 | 文档 67 的真实处理结果 | `document_id=67` | 01:12 |
| 图5.2 | `figures/图5.2_简达助手实际咨询界面.png` | `http://127.0.0.1/assistant` | 390×844 | 已审核发布内容 RAG，2 条权威引用 | provider=`external`；model=`deepseek-v4-flash`；HTTP 200；1479 Token；3193 ms | 引用 2 条已发布内容 | 01:00 |
| 图5.3 | `figures/图5.3_居民端适老化阅读界面.png` | `http://127.0.0.1/guide/guide-60` | 390×844 | 真实已发布办事指南；真实点击切换 24px | 不涉及新调用 | `slug=guide-60` | 01:00 |
| 图7.1 | `figures/图7.1_真实浏览器集成测试结果.png` | 首页、助手、处理进度、原文审核 2×2 | 1600×1200 | 本轮四张真实浏览器截图 | 助手调用同图5.2；处理任务见图8.3b | `guide-60`、`document_id=102/67` | 01:14 |
| 图8.1 | `figures/图8.1_居民端主要操作界面.png` | 首页、详情、个人中心三联图 | 1320×940 | `demo_chen` 真实登录、地区 `310113102` | 不涉及新调用 | `slug=guide-60` | 01:14 |
| 图8.2 | `figures/图8.2_简达助手使用界面.png` | `http://127.0.0.1/assistant` | 390×844 | 同图5.2，问题、回答、来源和输入框同屏 | provider=`external`；model=`deepseek-v4-flash`；HTTP 200；1479 Token | 引用 2 条已发布内容 | 01:00 |
| 图8.3a | `figures/图8.3a_机构端材料录入界面.png` | `http://127.0.0.1:8090/documents/upload` | 1440×900 | 真实机构端上传页面 | 不涉及新调用 | 无 | 01:01 |
| 图8.3b | `figures/图8.3b_智能处理进度界面.png` | `http://127.0.0.1:8090/documents/102/process?jobId=128` | 1440×900 | 真实 Processing Job；截图时 35%、已耗时 1 秒 | provider=`external`；model=`deepseek-v4-flash`；已验证结果缓存复用；最终 `SUCCEEDED` | `document_id=102`、`job_id=128` | 01:10 |
| 图8.3c | `figures/图8.3c_原文与生成结果审核界面.png` | `http://127.0.0.1:8090/documents/67/review` | 1440×900 | 原文与已确认字段同时可见 | 文档 67 的真实处理结果 | `document_id=67` | 01:12 |
| 图8.3组合 | `figures/图8.3_机构端材料处理与审核发布界面_组合.png` | 图8.3a/b/c 纵向组合 | 1320×2640 | 三张真实截图，白底、编号、短标题，保持原始比例 | 同各子图 | `document_id=102/67` | 01:14 |

## 原始证据

- 居民端：`raw/resident/`
  - `首页_390x844_20260831_region-310113102.png`
  - `适老详情_390x844_20260831_guide-60_font-24.png`
  - `个人中心_390x844_20260831_demo-resident.png`
  - `简达助手_390x844_20260831_real-rag.png`
- 机构端：`raw/institution/`
  - `材料录入_1440x900_20260831.png`
  - `智能处理进度_1440x900_20260831_doc-102_job-128.png`
  - `原文对照审核_1440x900_20260831_doc-67.png`
- 修复前诊断证据：`raw/institution/智能处理进度_1440x900_20260831_doc-102_job-127_before-fix.png`，仅用于说明 28800 秒耗时偏差，不属于最终报告图。
- 自动截图摘要：`logs/capture-summary.json`

## 真实交互与质量检查

- 居民端通过登录页真实登录；机构端通过登录页真实登录。
- 适老字号通过设置页按钮真实切换，`.reader` 计算字号为 `24px`。
- 简达助手问题为“老年人如何防范电信网络诈骗？请给出平台已审核来源。”，模式为“已审核内容 + AI 整理”，引用 2 条。
- 页面所有真实 `img` 均通过 `naturalWidth > 0`、`naturalHeight > 0` 检查。
- 机构端处理任务 `job_id=128` 为真实任务，启动接口 HTTP 200；截图时已耗时 1 秒，控制台错误 0；最终状态 `SUCCEEDED`。
- 审核页通过真实“查看全部”交互展开已确认字段，原文与结构化结果同时可见。

## Docker、迁移与 Gate

- Docker：MySQL、AI Service、Backend、Frontend 均为 `healthy`。
- 健康检查：`8001/health`、`8080/actuator/health`、`8090/health`、`80/health` 均成功。
- `npm run typecheck`：通过（机构端、居民端）。
- `npm run build`：通过（机构端、居民端）。
- `mvn -f services/backend/pom.xml -Dtest=DocumentProcessingAsyncIntegrationTest test`：2 项测试通过。
- Docker 重建：`docker compose build backend frontend` 通过，重启后健康。
- V41 迁移审计：数据库中列与索引均已存在；远程仅将等价 MySQL 多语句格式改为标准 SQL，语义未变。为兼容已执行数据库，将 `flyway_schema_history` 的 V41 checksum 从 `990594481` 更新为当前文件的 `685498337`；未删除数据、未删除 volume、未重跑历史迁移。

## 截图阻塞修复

- 修复 `ProcessingView` 首屏轮询数据尚未返回时访问 `latestJob.stage` 的竞态，修复后控制台错误为 0。
- 处理耗时改由 MySQL 使用同一时钟计算，避免 JVM/数据库时区差导致新任务显示约 8 小时。
- 代码修复提交：`4427526 fix(机构端): 修复处理进度首屏竞态与耗时偏差`。
