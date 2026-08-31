# H5 墨绿视觉统一验收记录

日期：2026-08-31

环境：Docker 本地真实服务，Chromium，390 × 844

结论：PASS

## 本轮修复

- 移除首页主区块之间遗留的全局 48px 间距，搜索、频道和 Hero 连续排列。
- 统一 H5 墨绿、暖白、正文、弱化文字和边框设计变量，蓝色降级为辅助色。
- 首页频道、搜索按钮、Hero、快捷服务、内容流、底栏当前态和助手气泡统一为墨绿体系。
- 移动端 Hero 图片固定为 190px，正文摘要最多两行，高频服务进入首屏。
- 登录和注册页复用 `HeartHandshake` 品牌图标，Tab、输入焦点、主按钮和链接统一为墨绿。
- 修复顶栏品牌图标被通用 SVG 颜色规则覆盖的问题。
- 对异常的空来源或纯问号来源使用“权威来源”安全展示；未修改后端业务数据。

## 自动验收结果

- 390 × 844 无横向溢出：PASS
- 搜索文案保持单行：PASS
- 搜索到频道间距不超过 30px：PASS
- 频道到 Hero 间距不超过 20px：PASS
- Hero 使用非分类默认图，图片自然尺寸有效：PASS
- 高频道选中态为 `#0E5A55`，未选中态为深绿：PASS
- 高频道服务至少 44px 进入底栏上方可视区：PASS
- 助手气泡宽度 58–62px、墨绿背景：PASS
- 首页不显示连续问号异常来源：PASS
- 登录品牌、Tab、按钮和键盘焦点环为墨绿：PASS
- 设置页当前字号操作为墨绿：PASS
- 页面脚本异常与 console error：0
- 专用 REAL Playwright：3 passed

## 截图

- `01_home_390x844_final_green.png`
- `02_home_top_no_blank_space.png`
- `03_home_real_hero.png`
- `04_home_channels_green.png`
- `05_home_assistant_bubble.png`
- `06_login_390x844_green.png`
- `07_login_username_tab_green.png`
- `08_search_or_settings_green_consistency.png`

## 已知数据说明

真实发布内容中存在来源名称仅由问号组成的历史数据。当前 H5 已避免将异常字符直接暴露给居民；本轮按任务边界未修改数据库、采集链路或地区业务逻辑。
