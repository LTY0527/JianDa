# 简达居民端视觉与帖子上下文问答验收

验收日期：2026-08-31

分支：`final/pre-acceptance-nightly-20260831`

真实环境：Docker Compose、Chromium、390×844、真实本地 API、无前端 API Mock

## 1. 登录 Logo 统一

登录页、注册页与首页继续复用 Lucide `HeartHandshake` 品牌图形。修复了旧结构样式把图标所有路径强制填白、最终看成“纯爱心”的问题；登录页现在显示与首页一致的白色线性握手爱心图形，置于墨绿色圆角方形中。

## 2. 页面背景

居民端画布统一为白色 `#FFFFFF`，仅在卡片层使用 `#F7FAF9`、`#EFF6F4` 等极浅冷灰绿。清理了登录、助手和个人页的暖米色画布，避免墨绿、蓝色与黄米色同时出现。

## 3. 蓝色替换

旧结构样式中的 `#0057B8` 全部替换为次级青绿 `#2F7771`，旧 hover 蓝 `#004494` 替换为深墨绿 `#0A4642`。主色继续使用 `#0E5A55`，浅背景使用 `#E8F3F1`。H5 Vue/CSS 中已无本轮列出的高饱和蓝色字面量。

## 4. 首页左侧留白

390px 下混合信息流使用 20px 水平留白；REAL Gate 断言第一条信息正文左坐标不少于 18px。标题、摘要、图片和箭头共同服从同一容器，不再单独给标题补边距。

## 5. 底栏助手位置

保留约 60px 的悬浮助手圆钮，文字相对原位置上移并完整落在可视区内。390×844 REAL Gate 实测文字下边缘距视口底部不少于 4px，且无横向滚动。

## 6. 全 App 节奏收紧

移动端通用相邻模块间距由旧结构层强制的 48px 收敛为 20px；首页、服务页、个人页、登录页及助手输入区分别使用更具体的 12–20px 节奏。保留不小于 48px 的核心触控区域。

## 7. Profile 统计与菜单

旧移动规则曾将四项统计强制为单列，实测高度达到 377px；现恢复一行四列，统计卡实测约 96px。菜单说明改为单行省略，菜单行固定 64px。会员横幅改为墨绿—青绿渐变并修复了未定义变量导致背景透明、白字不可见的问题。

## 8. Services 搜索框

搜索容器为 52px flex 行，图标和输入框垂直中心差不超过 2px；内部 input 边框为 0。焦点落入输入框后，外层容器显示 `#2F7771` 边框和同色透明焦点环。

## 9. 帖子上下文前端链路

详情页“询问这个事项”跳转到：

`/assistant?mode=context&slug=social-security-card-renewal`

助手从真实详情接口加载标题、分类和地区，固定输入区显示“正在基于这篇内容提问”、真实标题、查看原内容与退出入口。上下文模式使用专属 placeholder 和三个快捷问题；进入后输入区自动进入视口。退出后 query、上下文卡和专属 placeholder 立即清除。

## 10. contextSlug 后端生效

请求监听确认真实 `/api/public/assistant/chat` POST body 携带与详情一致的 `contextSlug`。后端先按当前地区读取可见 `PUBLISHED` 内容，再校验上下文 slug；缺失、撤回或跨地区内容返回 404，不再降级成普通检索或通用 AI。真实回答引用“社会保障卡到期换领指南”，并依据材料回答“本人身份证原件、原社会保障卡”。

## 11. NO_EVIDENCE

上下文模式仍复用证据覆盖规则。新增集成测试证明：材料未支持的高风险事实不会因为存在 `contextSlug` 而被猜测，返回无依据提示；失效上下文不会发起回答生成。普通模式退出后请求不再携带 `contextSlug`。

## 12. 构建与后端测试

- `npm run typecheck --workspace apps/user-h5`：PASS。
- `npm run build --workspace apps/user-h5`：PASS，Vite 转换 1767 modules。
- `mvn -f services/backend/pom.xml -Dtest=AssistantIntegrationTest test`：PASS，15 tests。
- `mvn -f services/backend/pom.xml test`：PASS，114 tests，0 failure，0 error，0 skipped，BUILD SUCCESS。
- `docker compose build frontend`：PASS。
- `docker compose up -d frontend`：PASS。

## 13. Playwright

命令：

`npx playwright test tests/e2e/real/urgent-h5-final-polish.spec.ts`

结果：5 passed（最终一轮 12.2s）。覆盖白色青绿主题、品牌图形、首页留白、底栏、Profile、Services 搜索、真实上下文请求/引用/退出、普通模式及不可见上下文。测试未拦截或伪造 API 响应。

## 14. 截图

全部为真实 Docker 页面、真实 Chromium、390×844：

1. `01_home_final_white_teal_390x844.png`
2. `02_home_feed_left_padding.png`
3. `03_login_brand_logo_white_bg.png`
4. `04_bottom_assistant_label_fixed.png`
5. `05_profile_compact.png`
6. `06_services_search_aligned.png`
7. `07_detail_ask_this_item.png`
8. `08_assistant_context_mode_bottom.png`
9. `09_assistant_context_answer_citation.png`
10. `10_assistant_normal_mode.png`

## 15. 剩余风险

- 上下文输入区为固定布局；虽然已恢复 360px 内容底部留白、缩短 textarea，并用消息锚点保证引用可点击，但极长标题和系统级超大字体组合仍建议在真机 Safari 再做一次人工观察。
- 全量 Maven 日志仍有 H2 版本高于当前 Flyway 已验证范围的升级建议，不影响本轮 114 项测试通过。
- 本轮只验证 Chromium；iOS Safari 的键盘弹起与 safe-area 动态变化不在桌面 Chromium 可完全模拟的范围内。

## Docker 健康结果

- AI `http://127.0.0.1:8001/health`：HTTP 200。
- Backend `http://127.0.0.1:8080/actuator/health`：HTTP 200。
- Institution `http://127.0.0.1:8090/health`：HTTP 200。
- H5 `http://127.0.0.1/health`：HTTP 200。
- MySQL、AI、Backend、Frontend 均为 `healthy`。

最终结论：PASS。
