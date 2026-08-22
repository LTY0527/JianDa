# Phase 9.5 商业化体验截图索引

所有截图均由仓库 Playwright Chromium 从真实运行页面生成。截图不包含密码、Token、Authorization、API Key 或 DevTools。

## 修改前

| 文件 | 页面 | viewport | 角色 | 说明 |
| --- | --- | --- | --- | --- |
| `before/h5-home-375.png` | 用户端首页 | 375×812 | 居民游客 | Phase 9.5 修改前移动端首屏 |
| `before/h5-home-1440.png` | 用户端首页 | 1440×900 | 居民游客 | Phase 9.5 修改前桌面布局 |
| `before/admin-dashboard-1440.png` | 机构端工作台 | 1440×900 | 平台管理员 | 修改前七项侧栏和高密度指标工作台 |
| `before/admin-content-1440.png` | 机构端材料管理 | 1440×900 | 平台管理员 | 修改前分散的材料列表入口 |
| `before/admin-auto-collection-1440.png` | 机构端来源管理 | 1440×900 | 平台管理员 | 修改前采集与来源页面 |

## 修改后

| 文件 | 页面 | viewport | 角色 | 验收重点 |
| --- | --- | --- | --- | --- |
| `after/h5-home-375.png` | 首页 | 375×812 | 游客 | 移动首屏与五项导航 |
| `after/h5-home-390.png` | 首页 | 390×844 | 游客 | iPhone 常见宽度 |
| `after/h5-home-768.png` | 首页 | 768×1024 | 游客 | 平板响应式 |
| `after/h5-home-1440.png` | 首页 | 1440×900 | 游客 | 桌面响应式 |
| `after/h5-location-picker-390.png` | 地区选择 | 390×844 | 游客 | 大场镇试点与未开放地区 |
| `after/h5-service-directory-390.png` | 办事目录 | 390×844 | 游客 | 缺失信息不伪造 |
| `after/h5-detail-390.png` | 内容详情 | 390×844 | 游客 | 动态字段与适老阅读 |
| `after/h5-neighborhood-390.png` | 大场邻里 | 390×844 | 游客 | DEMO 标识与隐私提示 |
| `after/h5-profile-390.png` | 我的 | 390×844 | DEMO 居民 | 登录后个人任务入口，不展示凭据 |
| `after/h5-reminders-390.png` | 我的提醒 | 390×844 | 游客 | 真实内容时间提醒 |
| `after/admin-dashboard-1440.png` | 工作台 | 1440×900 | 平台管理员 | 今日待办与操作减法 |
| `after/admin-content-1440.png` | 内容中心 | 1440×900 | 平台管理员 | 统一内容状态 |
| `after/admin-auto-collection-1440.png` | 采集与来源 | 1440×900 | 平台管理员 | 来源健康卡与一键检查 |
| `after/admin-review-1440.png` | 原文对照审核 | 1440×900 | 平台管理员 | 风险聚焦与可追溯字段 |
| `after/admin-publish-preview-1440.png` | 审核与发布 | 1440×900 | 平台管理员 | 真实用户端预览 |
| `after/admin-analytics-1440.png` | 数据概览 | 1440×900 | 平台管理员 | 数据库真实运营指标 |
| `after/admin-community-moderation-1440.png` | 邻里内容治理 | 1440×900 | 平台管理员 | 举报、隐藏与恢复入口 |

生成命令使用 `JIANDA_PHASE95_SCREENSHOT_STAGE=after` 和独立的 Phase 9.5 目录。普通测试继续写 Playwright 输出目录，不会覆盖 Phase 7.3 历史截图。
