# 简达验收前加急 UI 与地区链路验收报告

日期：2026-08-31

分支：`final/pre-acceptance-nightly-20260831`

验收视口：390×844（居民端与机构端关键页面）

## 1. 修改前问题

- 居民首页 Hero 可能被分类默认 SVG 抢占，真实封面没有绝对优先级。
- 390px 首页搜索、频道、Hero 间距偏大，平台主色被后加载的蓝色结构样式覆盖。
- “简达助手”主操作视觉不足，不符合悬浮圆形气泡要求。
- 详情页连续阅读使用 `router.push()`，每切换一篇都会增加历史记录，返回需要多次点击。
- 文件上传、网页导入与手工录入未完整接通三镇属地选择和 `region-scope` 写入。
- 发布成功链接未携带合法街镇上下文；全国共享 code 也可能被错误带入 H5 query。
- 后端地域写入没有同时校验省、市、区、镇名与 code 的完整映射。
- 视觉验收发现全局 `#app h1 !important` 把深绿 Hero 标题覆盖为黑色，实际对比度不合格。

## 2. 主要修改文件

- 机构端：`RegionTownSelector.vue`、`regions.ts`、`UploadView.vue`、`WebArticleImportPanel.vue`、`PublicImportView.vue`、`PublishView.vue`、`PublishedView.vue`、`h5-url.ts`、文档与来源 API 类型。
- 居民端：`HomeView.vue`、`DetailView.vue`、`router.ts`、`styles.css`、`soft-structuralism.css`。
- 后端：`DocumentService.java`、`SupportedRegions.java`。
- 测试：`Phase992RegionMembershipIntegrationTest.java`、`publish-link.spec.ts`、`h5-detail-navigation.spec.ts`、`real/urgent-final-ui.spec.ts`。

## 3. 首页真实图优先规则

当前地区、当前频道范围内先筛选 `hasRealCover(item) === true`，再按既有 `rank()` 排序。只要存在合法真实封面，Hero 就不会被默认 SVG 抢占。当前真实封面加载失败时记录该内容并切换到下一条真实封面；真实候选全部失败后才使用无图文字 Hero。地区筛选仍由服务端和当前 `regionCode` 约束，不跨镇借图。

## 4. 390×844 布局与视觉

- 搜索提示为单行省略，按钮高度不低于 44px。
- 顶栏、搜索、频道、Hero 间距压缩，首屏能更早看到核心内容。
- Hero 真图高度 210px，`object-fit: cover`，页面无横向溢出。
- 墨绿 `#0E5A55/#0A4642` 为主色，蓝色仅用于搜索、链接和少量图标。
- 深绿 Hero 标题为白色，摘要和来源为浅青白；截图目检及 computed style 断言均通过。

## 5. 简达助手气泡

复用现有 `BottomNav.vue` 的 `bottom-nav__primary`，圆形主按钮为 60px、4px 白边、墨绿背景、克制高光与阴影，上浮 24px，并保留“简达助手”文字和 safe-area。

## 6. 返回 Bug 根因与修复

根因是详情连续阅读统一调用 `router.push()`，A→B→C 会连续写入历史记录。现在滑动、上一篇/下一篇、桌面侧边按钮和键盘导航共同调用的 `navigateTo()` 使用 `router.replace()`。真实流程连续切换四次后，左上返回一次回到原首页。直接访问详情时仍使用现有安全返回 fallback。

## 7. 上传属地链与三镇 code

三个主动导入入口统一显示必选街镇：

- 大场镇：`310113102`
- 顾村镇：`310113109`
- 庙行镇：`310113112`

固定映射为上海市 / 上海市 / 宝山区 / `LOCAL_TOWN`。文件上传顺序为：创建材料 → 写入 `region-scope` → 上传原文件 → 创建处理任务；属地写入失败时不会继续处理。网页预览可从可信来源预填已支持 code，但仍在 UI 中可见且可修改；手工录入同样强制选择。

后端按省、市、区、镇名、code 全组合校验。组织内有文档访问权的账号可以为本组织材料设置属地，不能修改其他组织材料。

## 8. 发布与地区隔离真实验证

真实浏览器通过机构端上传公开反诈 PDF，选择顾村镇，生成并发布 `guide-127`。公开 API 实测：

- 顾村 `310113109`：匹配 1 条，`LOCAL_TOWN / 宝山区 / 顾村镇 / 310113109`。
- 大场 `310113102`：匹配 0 条。
- 庙行 `310113112`：匹配 0 条。

发布链接仅在 code 属于上述三镇时增加 `?region=`；`100000` 等共享范围 code 不会写入 H5 query。H5 也只接收三镇合法 code。

本次地区链写入验收使用真实页面、真实 HTTP API、真实 MySQL 和真实上传文件，没有 Playwright route mock。当前容器原为 External Provider；首次处理文档 126 时，External 对该 PDF 返回 `schema_invalid=8`，后端按追溯规则拒绝结果。由于本轮验收目标是地区链而非 External 模型质量，随后临时使用项目内置确定性 MockProvider 完成文档 127 的处理/审核/发布，完成后已恢复 External Provider。该失败未被冒充为 External PASS。

## 9. 构建和自动测试

- `npm --prefix apps/user-h5 run typecheck`：PASS。
- `npm --prefix apps/user-h5 run build`：PASS，Vite 1767 modules。
- `npm --prefix apps/institution-web run typecheck`：PASS。
- `npm --prefix apps/institution-web run build`：PASS，Vite 1703 modules。
- `mvn -f services/backend/pom.xml test`：PASS，111 tests，0 failures，0 errors，0 skipped。
- `Phase992RegionMembershipIntegrationTest`：PASS，8 tests；覆盖三镇、组织账号写入、镇名/code 不匹配拒绝、发布字段保留和跨镇隔离。
- 写入型 REAL Playwright：PASS，1/1；覆盖顾村上传、处理、审核、发布和跨镇隔离。
- 相关 Playwright 回归：12 passed，1 skipped；跳过项是默认关闭的写入型用例（已在前一步显式开启并通过）。覆盖连续阅读、375px 大字/滑动、发布链接和 390×844 REAL 页面。
- `docker compose build frontend`：PASS；已部署最新机构端和居民端。

## 10. Docker 与健康检查

`mysql`、`ai-service`、`backend`、`frontend` 均为 healthy。以下地址均返回 HTTP 200：

- `http://127.0.0.1:8001/health`
- `http://127.0.0.1:8080/actuator/health`
- `http://127.0.0.1:8090/health`
- `http://127.0.0.1/health`

验收结束时 `LLM_PROVIDER=external`，未输出或修改 API Key。

## 11. 截图证据

- `01_home_390x844_real_cover_green_theme.png`
- `02_home_390x844_assistant_bubble.png`
- `03_detail_after_swipes_before_back.png`
- `04_back_once_returns_home.png`
- `05_upload_region_selector.png`
- `06_gucun_home_after_region_publish.png`

六张截图均来自 Docker 本地系统和 Playwright Chromium 的 390×844 真浏览器页面，未使用静态 HTML、图片编辑或接口拦截。截图 06 的 Hero 仍是顾村可见范围内的合法真实图片文章；新发布反诈 PDF 本身无公开真实封面，因此没有用默认 SVG 或无关图片强行替换 Hero。

## 12. 视觉复核记录

验收依据为本次加急 MD 规范，没有单独的位图设计稿。逐张 `view_image` 复核：

1. 信息层级：顶栏 → 搜索 → 频道 → Hero 清晰，首屏无大块无意义空白。
2. 字体与对比度：Hero 标题白色、摘要浅青白，修复了全局黑色标题覆盖。
3. 配色：墨绿为主体，蓝色仅作为定位、搜索、链接辅助。
4. 图片：真实封面自然裁切，无拉伸、破图和默认 SVG 冒充 Hero。
5. 导航：60px 助手气泡居中、不裁切；详情切换后一次返回首页。
6. 响应式：390×844 无横向溢出，搜索提示不换行，按钮触控尺寸合格。

上首屏可见文案与需求规定一致，未新增无关营销标签、徽章或装饰性文案。

## 13. 剩余风险

- External Provider 对本次反诈 PDF 的真实处理仍存在 `schema_invalid=8`，属于模型结构化输出/追溯质量问题，不属于本轮地区链 UI 修复；现场文档 126 保留为 FAILED，未伪造成功。
- 本轮自动浏览器为 Playwright Chromium；未在 Safari/WebKit 真机重复全部流程。
- 顾村新发布材料没有合法真实封面，因此按产品规则不会成为大图 Hero；其地区可见性已通过真实公开 API 验证。

结论：本轮加急 UI、连续阅读返回、三镇属地写入、发布透传与跨镇隔离验收 PASS；External 对特定反诈 PDF 的结构化质量单独记录为后续风险。
