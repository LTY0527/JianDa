# Phase 9.3——资讯采集运营化、配图审核与连续阅读体验

## 起点

- 起始提交：3ca103f
- 当前分支：feat/phase9-3-crawl-operations-v1
- Schema 恢复基线：3ca3c9d、fcc45af、3ca103f

## 阶段目标

1. 在既有 source_registry、crawl_job 与 WebArticleService 基础上增加来源运营配置和安全调度框架。
2. 建立 RSS/Atom、Sitemap、JSON-LD、栏目链接的有界文章发现。
3. 增加图片候选元数据、权利审核和公开封面安全回退。
4. 提供采集任务中心、失败重试、内容变化版本保护。
5. 增加公开资讯上一篇/下一篇、路由重载、桌面点击、移动滑动和阅读设置。
6. 默认关闭定时采集真实 AI，使用文章数与 token 双预算保护。

## 里程碑

- [x] 数据库与配置基础
- [x] 来源管理和调度锁
- [x] 通用文章发现与 Fixture
- [x] 任务中心与错误队列
- [x] 图片候选审核
- [x] 内容变化版本
- [x] 连续阅读与设置
- [ ] 首页小幅优化
- [ ] 自动化、Playwright、Docker 和受控采集验证

## 安全边界

- 只处理启用白名单来源，遵守 robots、SSRF 和单站限速。
- 新来源默认停用，默认禁止图片公开缓存并必须人工审核。
- 未确认图片不得成为用户端公开封面。
- CRAWL_SCHEDULER_ENABLED 和 CRAWL_AUTO_AI_ENABLED 默认 false。
- Fixture 不含真实完整文章；自动测试不访问真实网络和真实模型。
- 不自动审核、不自动发布、不 push、不删除 Docker volumes。

## 测试结果

Phase 9.3-A 连续阅读收尾（2026-07-29）：

- 后端公开接口集成测试 4 项通过，其中新增 3 项覆盖 pinned、importance、published_at、id 稳定排序，同分类逐方向优先及全局回退、撤回过滤和首尾边界。
- Playwright Chromium 连续阅读测试 3 项通过，覆盖桌面侧边按钮、键盘、浏览器前进后退、路由加载状态隔离、375px 左右滑动、交互控件排除、pointercancel、纵向与短距离手势、正文选择和首尾禁用状态。
- user-h5 与 institution-web 的 typecheck、生产构建均通过。
- Phase 9.3-B 来源运营与调度锁集成测试 3 项通过：覆盖新来源安全默认值、URL/域名/协议校验、平台管理员权限隔离、更新与启停、敏感字段响应排除、租约竞争、持有者释放和过期接管；V13 在 H2 MySQL 模式下由 Flyway 成功升级。
- institution-web 来源运营页面 typecheck 与生产构建通过，新增编辑、启停确认、调度和预算摘要、加载/空状态与错误摘要。
- Phase 9.3-C 离线文章发现测试 14 项通过：覆盖 RSS 2.0、Atom、Sitemap、Sitemap index、JSON-LD Article/NewsArticle、栏目页链接、相对地址、canonical 去 fragment/default port、重复过滤、非法协议、外域链接、空栏目、XML 错误/深度限制、响应体上限、手动重定向上限、robots、SSRF/DNS 与单域限速复用。
- 后端发现接入与来源回归测试 5 项通过：仅启用白名单来源可发现、同源入口校验、候选二次去重、部分失败状态、平台管理员权限边界，并确认发现过程不创建 published_item、不审核、不发布且不调用 AI 分析。
- Phase 9.3-D 任务中心及来源/发现回归测试 9 项通过：覆盖 SUCCESS、PARTIAL_SUCCESS、FAILED、CANCELLED、DISABLED/PENDING/RUNNING 状态基础，任务计数、单条和批量重试、不可重试、最大 3 次、有界退避、同 URL 幂等、同来源租约竞争、取消释放、403 权限和 Authorization/Cookie/API Key/堆栈/URL 用户信息脱敏；V14 迁移成功。
- institution-web 任务中心 typecheck 与生产构建通过，提供状态/来源筛选、任务详情、计数摘要、错误队列、单条/整批重试、取消确认和空状态。自动 AI 执行及图片审核未纳入本阶段。
- Phase 9.3-E 后端版本、图片审核与任务回归 8 项通过：正文 hash 未变化不创建版本，变化后保存 canonical、版本根/版本号、旧新 SHA-256、采集时间和变更摘要；已发布版本保持 PUBLISHED 且公开指针不变，新版本进入 WAITING_REVIEW，多次无变化不增版本。
- 图片候选从 OpenGraph、JSON-LD 和正文图片统一持久化 URL、来源页、方法、alt、尺寸、MIME、hash、缓存、权利和审核状态；Logo/二维码/广告/头像/图标/追踪像素、小尺寸及异常比例继续过滤。发布前必须由人工填写来源和许可说明并确认，拒绝或无候选时回退分类默认图。
- ai-service 图片过滤回归 7 项、institution-web 图片候选审核 typecheck/生产构建通过；未调用真实 AI，未自动审核或发布。
- 本阶段未调用真实 DeepSeek，未读取真实 .env。
- 数据库与配置基础、来源管理和调度锁、通用文章发现与 Fixture、任务中心与错误队列、图片候选审核、内容变化版本、首页小幅优化及全量 Docker 验收仍待后续里程碑完成。

## 阻塞问题

暂无。工作区中 15 张 Phase 7.3 截图是进入本阶段前已存在的独立修改，不纳入功能提交且不丢弃。

## 最终提交

- bdc8501 feat(采集): 建立运营配置与任务数据基础
- 354ba37 feat(阅读): 增加上一篇下一篇与左右滑动切换
- Phase 9.3-A 连续阅读排序、手势边界与自动化验收提交待本次收尾生成。
