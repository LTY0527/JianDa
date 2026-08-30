# 简达验收前全功能真实测试报告

## 验收结论

- 验收日期：2026-08-31（Asia/Shanghai）
- 代码基线：`3b6fcdd`
- 分支：`docs/report-final-artifacts-20260831`
- 远端基线：当前 HEAD 包含 `origin/main`，归档前领先远端 6 个提交
- 最终结论：**PARTIAL**
- Gate：共 13 项，PASS 11、PARTIAL 2、FAIL 0、BLOCKED 0

PARTIAL 不代表已验证核心业务失败。Gate 1 的历史根 Playwright 套件尚未全部迁移到当前居民登录门禁；Gate 10 的停止 AI 容器故障注入因执行策略未实际执行。两项均未用 Mock、降低断言或伪造终态冒充 PASS。

## 测试环境

| 项目 | 实际值 |
| --- | --- |
| 操作系统 | Windows / PowerShell |
| 日期 | 2026-08-31 |
| 数据库 | Docker MySQL，真实持久化数据 |
| AI Provider | `external` |
| AI Model | `deepseek-v4-flash` |
| 支付能力 | `LOCAL_TEST`，不表述为真实商业支付 |
| 浏览器路径 | Browser 插件不可用，按前端测试规范使用仓库 Playwright Chromium |
| 用户端视口 | 以 390×844 为主，并验证字号、触控和响应式状态 |

最终 REAL Playwright 未使用 `page.route` 请求拦截、静态伪数据或 Mock Provider。旧测试中的 Mock 仅计入自动回归，不计入真实验收 PASS。

## Docker 与健康检查

最终复查时以下服务均为 `healthy`：

| 服务 | 端口 | 结果 |
| --- | --- | --- |
| MySQL | 3307 → 3306 | healthy |
| AI Service | 8001 | healthy；`/health` HTTP 200 |
| Backend | 8080 | healthy；`/actuator/health` HTTP 200 |
| Frontend | 80 / 8090 | healthy；两个 `/health` 均 HTTP 200 |

未执行 `docker compose down -v`，未删除或重建用户持久化卷。

## 自动回归（不等同于最终 REAL 验收）

| 命令 | 真实结果 | 耗时/数量 |
| --- | --- | --- |
| `npm install` | PASS，exit 0 | 1.419s |
| `npm run typecheck` | PASS，exit 0 | 6.697s |
| `npm run build` | PASS，exit 0 | 15.804s |
| `python -m pytest services/ai-service/tests -q` | PASS | 127 passed，5 warnings，53.57s |
| `mvn -f services/backend/pom.xml test` | PASS | 102 tests，0 failures，0 errors |
| 用户端 `typecheck` / `build`（最终修复后复跑） | PASS | 两项均成功 |
| 根 `npm run test:e2e` | PARTIAL | 初次因5173/5174未启动失败；Docker复跑开始125个根spec后，旧Phase 7.3用例因仍假定受保护H5路由公开而失败，随后停止重复超时 |

根 E2E 遗留是测试迁移债务：当前产品的居民路由已启用登录门禁，而部分历史用例仍使用旧页面标题和公开路由假设。真实认证、最终 H5 路由和核心业务流已由独立 REAL Playwright 验证。

## 最终真实验收矩阵摘要

| Gate | 内容 | 结果 | 关键证据 |
| --- | --- | --- | --- |
| 0 | Git 基线 | PASS | `origin/main` 是 HEAD 祖先 |
| 1 | 静态检查、构建、自动回归 | PARTIAL | 构建/AI/Maven通过；旧根E2E待登录迁移 |
| 2 | Docker 健康 | PASS | 四服务healthy、四健康地址200 |
| 3 | 身份认证与权限 | PASS | 居民真实登录退出；三机构角色后端权限 |
| 4 | 文件与公开网页接入 | PASS | PDF、图片、权威网页三种真实材料 |
| 5 | External DeepSeek | PASS | 3/3成功、Token > 0、Mock替代0 |
| 6 | 审核与发布闭环 | PASS | document 75真实审核发布并在H5可见 |
| 7 | 居民端主要功能 | PASS | 20路由、四档字号、438个触控目标 |
| 8 | 简达助手RAG | PASS | 10题覆盖证据回答、通用参考、拒答和状态 |
| 9 | 机构端主要页面 | PASS | 内容、上传、处理、审核、发布、来源、运行能力 |
| 10 | 异常与可靠性 | PARTIAL | 自动异常测试通过；容器停止注入未执行 |
| 11 | 安全基础检查 | PASS | 无tracked `.env`、bundle无密钥标记、权限/SSRF/CORS通过 |
| 12 | 最终数据重算 | PASS | JSON与CSV已按本轮证据生成 |

逐场景结果见 [FINAL_ACCEPTANCE_MATRIX.csv](./FINAL_ACCEPTANCE_MATRIX.csv)。

## External Provider 真实证据

本轮最终统计使用数据库中真实持久化的三类 External 处理记录，不再发起额外模型请求：

| 文档 | 类型 | 最终状态 | Total Token | 端到端耗时 | 字段追溯 |
| --- | --- | --- | ---: | ---: | ---: |
| 60 老年人健康管理服务指南 | PDF | PUBLISHED | 4,657 | 10,391ms | 4/4 |
| 75 13岁女孩免费接种HPV疫苗通知 | IMAGE | PUBLISHED | 4,696 | 11,531ms | 3/3 |
| 88 普陀区老年助餐优惠政策 | WEB_ARTICLE | PUBLISHED | 10,727 | 26,385ms | 8/8 |

汇总：3/3 成功，总 Token 20,080，平均 6,693.33，范围 4,657–10,727；平均耗时 16,102.33ms，范围 10,391–26,385ms；最终 REAL 验收 Mock 替代次数为 0。三个文档均到达人工审核阶段并完成发布闭环。系统持久化记录提供了本轮可核验的 total token；报告不虚构未单独留存的 prompt/completion 拆分值。

文档 75 在真实发布表单中将历史乱码标题修正为“13岁女孩免费接种HPV疫苗通知”，并通过业务 API 将区域修正为宝山区大场镇（`310113102`）；其后用户端详情真实可见。

## 简达助手真实问题集

共验证 10 题：

- 6 个已发布内容问题：均返回 `mode=ai`，每题至少一条与材料匹配的引用；
- 1 个普通低风险问题：返回 `mode=web_ai` 并提供 2 条引用，前端明确标识通用 AI 参考；
- 2 个虚假高风险事实（电话、金额）：均返回 `mode=retrieval`，没有证据时明确“不会猜测”；
- 1 个运行状态问题：直接返回 `mode=status`，不混入内容检索答案。

证据：[screenshots/assistant-eval-summary.json](./screenshots/assistant-eval-summary.json) 与 [screenshots/assistant-external-390.png](./screenshots/assistant-external-390.png)。

## 居民端真实浏览器结果

真实居民账号覆盖首页、频道、搜索、详情、原文、原文件、服务、社区卫生、长者食堂、便民电话、办事指南、活动、收藏、历史、提醒、阅读设置、地区、邻里、助手、会员等 20 条受保护路由。

- 18/20/22/24px 四档字号均通过 `getComputedStyle` 验证并可刷新保持；
- 搜索无结果时可见“带关键词问简达”入口；
- 页面真实图片自动断言 `naturalWidth > 0` 且 `naturalHeight > 0`；
- 20 条路由无横向滚动；
- 可见主要控件 438 个，尺寸达到 44×44 CSS px 的为 438 个，达标率 100%；
- 最终路由巡检浏览器控制台错误 0。

触控原始统计见 [screenshots/h5-touch-targets.json](./screenshots/h5-touch-targets.json)。`h5-home-*` 中较早生成的登录门禁截图不作为首页成功证据；有效证据采用 `h5-final-news-390.png`、`h5-published-result-390.png` 等最终认证后截图。

## 机构端与权限结果

真实平台账号验证了文档列表、已发布内容、添加内容对话框、上传、处理、原文对照审核、发布、来源采集和运行能力页面。后端权限抽查：

- `platform_admin`：文档、平台来源、运行能力均 HTTP 200；
- `org_admin`：文档 HTTP 200，平台来源 HTTP 403，运行能力 HTTP 200；
- `reviewer`：文档 HTTP 200，平台来源与运行能力均 HTTP 403。

这证明关键权限由服务端执行，而非仅靠前端隐藏按钮。

## 本轮发现并修复的缺陷

| Commit | 修复 |
| --- | --- |
| `31973f8` | 有效简短 External 回答不再产生多余重试，citation修复最多2次 |
| `2f9bc23` | 收紧助手高风险证据边界；修复重采集缓存键失效；修复注册提示与动态调度测试日期 |
| `06488c0` | 居民退出同步清理全局认证缓存；助手历史空状态触控区域达到44px |
| `3b6fcdd` | REAL Playwright适配居民登录门禁、更新陈旧选择器并隔离最终截图 |

## 安全检查

- `.env` 受 Git ignore 保护，Git 跟踪数量为 0；
- 高风险密钥文件跟踪数量为 0；
- 前端生产 bundle 中敏感标记命中数量为 0；
- SSRF 私有/回环地址拒绝、CORS 和机构权限测试包含在通过的 Maven 套件中；
- 未输出 API Key、Authorization 或 Bearer 内容；
- 历史 Phase 7.3、8.4、9.3 已跟踪截图目录未被修改。

## 未解决问题与验收风险

1. 历史根 Playwright 用例需要统一迁移到当前居民登录门禁和新标题/选择器；因此 Gate 1 为 PARTIAL。
2. AI 容器停止的 fault injection 因执行策略拒绝而未实施；没有重试危险命令。自动化 AI 失败、超时、重试与结构恢复测试已通过，因此 Gate 10 为 PARTIAL。
3. 本轮未运行 Lighthouse，报告不提供或推测 Accessibility 分数。
4. 本轮最终收口未重新执行 iPhone Safari 和 Android Chrome 物理真机；响应式与触控结论来自真实 Chromium 视口。
5. `LOCAL_TEST` 仅是本地支付流程能力，不应在课程报告中描述为真实商业支付接入。

## 可用于课程报告第七章的最终数字

请以本节替换旧报告中的沿用数字：

- 最终 Gate：13 项，PASS 11、PARTIAL 2、FAIL 0、BLOCKED 0，结论 PARTIAL；
- External 真实材料：3 份（PDF、图片、权威网页各 1 份），成功 3、失败 0；
- External Provider / Model：`external` / `deepseek-v4-flash`；
- External 总 Token：20,080；平均 6,693.33；范围 4,657–10,727；
- External 平均端到端耗时：16,102.33ms；范围 10,391–26,385ms；
- 最终 REAL Mock 替代：0 次；
- 简达助手真实问题：10 题，其中证据型 AI 6、低风险通用参考 1、安全拒答 2、运行状态 1；
- 居民端：20 条真实路由、438 个可见主要触控目标、438 个达标，达标率 100%，控制台错误 0；
- Docker：4 个服务 healthy，4 个健康接口 HTTP 200；
- 自动回归：AI 127 passed，后端 102 tests 全通过，两个前端 typecheck/build 通过；根 E2E 因历史登录门禁测试债务未全绿；
- Lighthouse Accessibility：本轮未运行，无实测分数。

结构化指标见 [data/final_metrics.json](./data/final_metrics.json) 和 [data/final_metrics.csv](./data/final_metrics.csv)。

## 关键截图证据

- 平台登录：[screenshots/platform-login.png](./screenshots/platform-login.png)
- 居民登录：[screenshots/resident-login.png](./screenshots/resident-login.png)
- 机构内容中心：[screenshots/admin-documents-1440.png](./screenshots/admin-documents-1440.png)
- 原文审核：[screenshots/admin-review-1440.png](./screenshots/admin-review-1440.png)
- 已发布内容：[screenshots/admin-published-1440.png](./screenshots/admin-published-1440.png)
- 运行能力：[screenshots/admin-runtime-capabilities-1440.png](./screenshots/admin-runtime-capabilities-1440.png)
- External 助手：[screenshots/assistant-external-390.png](./screenshots/assistant-external-390.png)
- 用户端资讯：[screenshots/h5-final-news-390.png](./screenshots/h5-final-news-390.png)
- 真实发布结果：[screenshots/h5-published-result-390.png](./screenshots/h5-published-result-390.png)
