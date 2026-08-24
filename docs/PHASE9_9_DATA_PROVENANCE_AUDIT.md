# Phase 9.9 数据来源审计

日期：2026-08-24，数据源为现有 Docker MySQL。

## 清理前

- 已登记来源：5。
- PUBLISHED：28。
- 已知 DemoDataInitializer 发布项：12。
- `is_demo=true` 邻里帖子：13。

## 精确清理

- 预览：[phase9-9-data-cleanup-preview.json](../artifacts/phase9-9-data-cleanup-preview.json)
- 结果：[phase9-9-data-cleanup-result.json](../artifacts/phase9-9-data-cleanup-result.json)
- 仅按 12 个固定 slug 删除发布映射，相关构造材料转为 `WITHDRAWN` 留存审计。
- 仅按 `is_demo=true` 删除帖子和关联互动记录。
- 未进行任何全表删除；真实 WEB、真实上传、居民账号和 volume 均保留。
- Docker 已设置 `JIANDA_DEMO_CONTENT_ENABLED=false`，重启不会重新补写可见演示内容；H2/测试默认仍可使用 fixture。

## 清理后来源分类

| 分类 | 数量 | 说明 |
| --- | ---: | --- |
| REAL_WEB | 10 | 有 canonical URL 的真实网页文章 |
| REAL_PDF / REAL_UPLOAD | 1 | 有文件 SHA-256 的已发布上传材料 |
| REAL_RESIDENT_POST | 1 | `is_demo=false` 且居民实际创建的可见帖子 |
| UNKNOWN | 5 | 历史 PDF 在 SHA-256 字段上线前发布，标题与现有真实测试材料一致，但缺少可机器证明的来源标记，保留而不冒充 REAL |
| DEMO_SEED（可见） | 0 | 固定 slug 发布项与 `is_demo=true` 帖子已清理 |
| FIXTURE | 仅测试目录 | 不进入 Docker H5 普通内容流 |

## 当前真实数字

- 来源：7（新增顾村、庙行官方栏目）。
- PUBLISHED：16。
- WAITING_REVIEW 文档：22。
- 地区分布：大场 2、宝山区共享 1、无地区/市级或国家共享 13。
- 已确认真实封面：4。
- UNKNOWN：5；因缺少可靠删除依据而保留，待人工补来源标记。

