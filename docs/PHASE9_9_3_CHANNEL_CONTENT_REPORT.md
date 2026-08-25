# Phase 9.9.3 频道模型与内容覆盖报告

> 验收日期：2026-08-25
> 栏目调整证据：`artifacts/phase9-9-3-final/p0a-channel-adjustment-real.json`

## 1. 频道模型（V38）

7 个首页频道枚举（与 H5 `HomeView.vue` 频道 nav 一致）：

| 枚举 | 中文 | H5 频道 key |
|---|---|---|
| HEALTH | 健康 | health |
| ELDERLY | 养老 | elderly |
| MEALS | 助餐 | meals |
| SERVICES | 办事 | services |
| FRAUD | 防诈 | fraud |
| ACTIVITY | 活动 | activity |
| COMMUNITY | 社区 | community |

V38 已为 `published_item` / `source_document` 增加 `publish_channel` 字段。AI 通用栏目分类器已补充，定向测试 4/4。

## 2. 已发布内容受控栏目调整 API（P0-A）

### 2.1 接口

```
PUT /api/documents/{id}/publication-channel
权限：仅机构/平台管理员
请求体：{ publishChannel: "HEALTH" | "ELDERLY" | ... }
```

### 2.2 不变量保证

- 不修改原文
- 不改变 source trace（source_url / slug / document_id 不变）
- 不需要撤回重发
- H5 立即按新栏目读取
- 推荐流排序同步更新
- 接口幂等
- 保留 audit log

### 2.3 真实验收（doc 76）

| 步骤 | HEALTH | ACTIVITY | invariant | original_url | processing_status |
|---|---|---|---|---|---|
| 调整前 | 9 | 4 | — | — | — |
| 调整到 ACTIVITY | 8 | 5 | preserved | unchanged | unchanged |
| 恢复回 HEALTH | 9 | 4 | preserved | unchanged | unchanged |

- 调整后真实在 H5 feed 中出现新频道、消失原频道
- `moved_to_activity_in_feed=true` / `restored_to_health_in_feed=true`
- `PUBLISHED_CHANNEL_ADJUSTMENT_ACCEPTANCE = PASS`

### 2.4 机构后台入口

`PublishedView.vue` 表格新增"栏目"列 + "调整栏目"按钮，点击后展示 7 栏目 picker（中文产品化文案，不显示 enum）。

本次发现 frontend 容器运行旧镜像缺该入口，已重建镜像修复，Playwright 真实验证 picker 渲染（`admin-published-channel-adjust-1440.png`）。

## 3. 7 频道内容覆盖（P0-C）

真实数据库查询（`published_item` WHERE `status='PUBLISHED'`）：

| 频道 | 数量 | 门槛 | 状态 |
|---|---|---|---|
| HEALTH | 9 | ≥5 | PASS |
| ELDERLY | 5 | ≥5 | PASS |
| MEALS | 6 | ≥5 | PASS |
| SERVICES | 5 | ≥5 | PASS |
| FRAUD | 5 | ≥5 | PASS |
| ACTIVITY | 5 | ≥5 | PASS |
| COMMUNITY | 5 | ≥5 | PASS |
| **合计** | **40** | 推荐 ≥20 | PASS |

## 4. 内容真实性

- 全部真实官方 URL / 官方 PDF
- 真实 source / 正文 / DeepSeek 处理 / 人工审核记录 / publish
- 来源包括：上海市政府、上海市民政局、上海市医疗保障局、国家卫生健康委员会、新华网、上海市民政局"十五五"规划等
- 禁止项均无：Mock article / Fixture / 虚构社区公告 / 为数量复制文章 / 直接写数据库伪造 PUBLISHED

## 5. 截图证据

- `h5-channels-390.png`：7 频道 + 推荐全部可切换有内容
- `admin-published-channel-adjust-1440.png`：机构端栏目调整 picker
- `admin-documents-1440.png`：内容中心 6 状态 Tab

## 6. Final Gate

```
CHANNEL_MODEL_ACCEPTANCE = PASS
PUBLISHED_CHANNEL_ADJUSTMENT_ACCEPTANCE = PASS
ADMIN_CHANNEL_PUBLISH_ACCEPTANCE = PASS
CHANNEL_CONTENT_COVERAGE_ACCEPTANCE = PASS
REAL_CONTENT_ONLY_ACCEPTANCE = PASS
```
