# Phase 9.9.2 地域隔离真实报告

公开内容统一使用 `LOCAL_TOWN / DISTRICT_SHARED / CITY_SHARED / NATIONAL_SHARED / UNCLASSIFIED`。`UNCLASSIFIED` 不进入地区首页；LOCAL 仅在完全匹配 `region_code` 时可见。首页、搜索、助手 RAG 和相邻内容共用同一规则。

真实 Docker API 结果：

| 地区 | region_code | 当前公开数 | LOCAL 串区数 |
| --- | --- | ---: | ---: |
| 大场镇 | 310113102 | 8 | 0 |
| 顾村镇 | 310113109 | 7 | 0 |
| 庙行镇 | 310113112 | 7 | 0 |

文档 63 是大场镇 `LOCAL_TOWN`；文档 27、31、32、34、62、67、68、78 已核对为 `NATIONAL_SHARED`。顾村、庙行当前没有可发布的 LOCAL 精品内容，因此“三镇 Top10 明显不同”只能记为 `PARTIAL`，没有用其他地区或演示内容补齐。

平台管理员可通过 `PUT /api/documents/{id}/region-scope` 修正归属。
