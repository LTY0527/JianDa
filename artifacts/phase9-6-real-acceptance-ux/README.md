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
| `real-e2e/03-review.png` | 真实 | 机构端原文对照审核 | 1440×900 | Docker / MySQL / 真实网页 / External Provider | document `63` | 展示大场镇原文、可追溯字段与人工修正结果 |
| `real-e2e/05-h5-published-real-photo.png` | 真实 | H5 已发布资讯详情 | 390×844 | Docker / MySQL / 真实网页 / External Provider | `news-63` | 文件名沿用验收清单；因原图缓存和公开使用依据未确认，页面诚实回退分类默认图，并非真实照片封面 |

说明：`real-e2e/05-h5-published-real-photo.png` 证明真实文章发布和移动端展示链路，不作为“真实来源图片已公开”的证据。当前真实图片候选已完成技术校验，但在许可依据确认前不得公开。
