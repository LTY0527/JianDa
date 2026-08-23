# Phase 9.6 截图索引

本目录分为：

- `before/`：改造前、使用当前真实或明确标记的隔离数据；
- `after/`：改造后视觉回归；
- `real-e2e/`：只允许真实 Docker、MySQL、来源、文件、External Provider 和业务写入产生的证据。

`real-e2e` 不得使用 `page.route`、`route.fulfill`、fixture 或 mock API。每张截图生成后必须在下表登记。

| 文件 | 真实/隔离 | 页面 | viewport | 数据来源 | document id / slug | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `before/h5-home-390.png` | 真实 | H5 首页 | 390×844 | Docker / MySQL | 首页公开列表 | Hero 为分类默认 SVG |
| `before/h5-detail-390.png` | 真实 | H5 详情 | 390×844 | Docker / MySQL | `news-34` | 已发布权威资讯详情 |
| `before/h5-assistant-390.png` | 真实 | 简达助手 | 390×844 | Docker / MySQL | 无 | 提问前状态 |
| `before/admin-auto-collection-1440.png` | 真实 | 采集与来源 | 1440×900 | Docker / MySQL | 5 个来源 | 来源健康卡现状 |
| `before/admin-dashboard-1440.png` | 真实 | 机构工作台 | 1440×900 | Docker / MySQL | 真实待办 | 工作台现状 |
