# Phase 9.6 真实全链路验收与产品升级清单

更新时间：2026-08-23

## P0：真实链路

- [x] 审计 Phase 9.5 本地与远端基线，并建立独立分支。
- [x] 建立真实验收口径、问题台账和截图目录，不触碰 Phase 7.3 历史证据。
- [x] 审计网页历史图片、候选、审核、缓存、公开封面和首页回退的完整链路；真实基线覆盖率为 0%。
- [x] 保存 H5 首页/详情/助手与机构端采集/工作台的 before 截图。
- [x] 校验 Docker、MySQL、Flyway V26 与四项健康检查，四服务均为 healthy、四接口均为 HTTP 200。
- [x] 校验 External Provider 已通过真实网页处理返回 provider/model/token/耗时；未输出敏感配置。
- [x] 完成大场镇真实来源 discover → shadow → collect → AI queue → External → review → publish，发布为 `news-63`。
- [ ] BLOCKED：官方 PDF 文档 67 已真实提取 8 页/8 segments，但发送给 External Provider 需要新的明确授权；未调用、未伪造结果。
- [ ] BLOCKED：文档 63 的真实候选 8 技术有效，但公开缓存和使用依据未确认，已拒绝并安全回退分类默认图。
- [x] 完成真实调度器触发验证（job 29）并恢复来源停用、自动采集关闭和 7 天窗口。

## P1：产品能力

- [x] 首页 Hero 优先真实图片；无合法图片或图片加载失败时使用文字 Hero，禁止默认 SVG 充当 Hero。
- [ ] 区分主图、普通图文、紧急通知和服务信息布局，并验证 375/390/768/1440。
- [x] 助手检索返回文档 ID；External 回答对日期、时间、电话和金额执行引用覆盖校验，已核对字段生成 factCards，高风险无依据继续拒答。
- [ ] 搜索无结果可带关键词进入助手，并明确平台资料未命中。
- [ ] 机构端来源、图片、审核、发布与运营页面增强真实状态和问题导向层级。
- [x] 完成居民登录、发帖、点赞、评论、举报、隐藏和恢复的真实 API 闭环；居民 1、帖子 4 留有可追踪 DEMO 标识。
- [x] 完成提醒、内容浏览、收听、电话/地址操作事件与运营指标真实增长验证：周阅读 63→64、收听 0→1、提醒 0→1。

## P2：质量与证据

- [x] 建立无 `page.route` / `route.fulfill` 的真实 Docker Playwright 套件与静态守卫；真实发布 1/1、居民运营 1/1 通过。
- [ ] PARTIAL：已保存真实审核和 H5 发布截图并登记；完整 after 多视口截图集尚未补齐。
- [ ] 校验全部图片 `naturalWidth`、`naturalHeight`，并验证 18/20/22/24px。
- [x] 运行 AI pytest 101、Maven 71、两端 typecheck/build、全量 Playwright 105 passed/13 skipped（118 total）和两组显式启用的真实浏览器 E2E。
- [x] 输出真实验收、采集、图片、助手、UX、测试与问题台账七份报告。
- [x] 更新 README、TASKS、API，并按真实阶段使用中文提交；不 push。
