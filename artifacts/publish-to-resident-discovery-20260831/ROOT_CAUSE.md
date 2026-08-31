# 发布成功但居民端刷不到、搜不到：根因记录

日期：2026-08-31
分支：`final/pre-acceptance-nightly-20260831`

## 追踪对象

本次首先追踪最新一条“直链可打开、居民端不可发现”的真实发布记录：

- `document_id=125`
- `published_item_id=88`
- `slug=news-125`
- 标题：`“鲁迅文学奖之夜”在上海“盛放”...`
- `published_at=2026-08-31 04:03:07`
- `expires_at=NULL`
- `publish_channel=ACTIVITY`
- `local_scope=UNSPECIFIED`
- `region_code=NULL`

修复前矩阵：

| 层级 | 结果 |
| --- | --- |
| 无地区参数的直接详情 | HTTP 200 |
| 大场镇 `/public/items` | NOT_FOUND |
| 大场镇 `/public/search` | NOT_FOUND |
| H5 首页 | NOT_FOUND |
| H5 搜索 | NOT_FOUND |

用于修复后完整正向闭环的最近合法真实记录：

- `document_id=127`
- `published_item_id=87`
- `slug=guide-127`
- 标题：`upload`
- `published_at=2026-08-31 01:57:56`
- `expires_at=NULL`
- `local_scope=LOCAL_TOWN`
- `street_or_town=顾村镇`
- `region_code=310113109`

## Root Cause 1：发布事务允许未分类内容进入 published_item

`DocumentService.publish()` 原先直接把材料上的 `local_scope`、`region_code` 写入 `published_item`，没有拒绝 `UNSPECIFIED/UNCLASSIFIED`，也没有校验 LOCAL_TOWN、DISTRICT_SHARED、CITY_SHARED、NATIONAL_SHARED 所需的行政区证据。

结果是机构端得到“发布成功”，数据库也存在 PUBLISHED 记录，但严格地区列表会正确排除该记录，形成假发布成功。

修复：发布前规范旧范围别名并严格校验行政区证据；未分类内容返回 HTTP 400，不再创建公开记录。没有关闭地区隔离，也没有修改现有记录的地区或发布时间。

## Root Cause 2：详情与列表的公开可见规则不一致

旧详情接口只检查 `status='PUBLISHED'`，没有检查 `expires_at` 和 `PublishedRegionScope`。因此 `news-125` 可以用 slug 直开，但列表和搜索不会返回。

修复：居民 H5 的详情、原文、助手上下文和服务入口统一携带当前 `regionCode`；后端详情在收到地区上下文时与列表复用同一过期和地区 predicate。机构端生成的居民端链接继续携带所属地区 query。

## Root Cause 3：H5 全局搜索没有调用后端搜索

旧 `/search` 先请求 `/public/items`，再只对当前有限列表执行前端 `includes()`。一旦内容没有进入列表，搜索必然失败；正文、步骤、结构化字段和来源名称也无法命中。

修复：`ListView` 对关键词做 250ms debounce，真实调用 `/api/public/search`，并监听 query、route query 和地区变化。后端搜索扩展到标题、摘要、分类、来源、已发布 generated content 和非 REJECTED extracted field，并使用 EXISTS 防止重复。

## Root Cause 4：首页缺少新发布保证和恢复刷新

首页仅按综合推荐排序截取有限条目，最近发布内容可能被历史高分内容挤出；居民页已打开时，切到机构端发布后再切回也不会重新请求。

修复：首页在不改变 Hero 规则的前提下，把 72 小时内最新可见内容作为 feed 保底项；首页和资讯页在 focus/visibility 恢复且距上次加载至少 5 秒时轻量刷新，并防止并发请求。

## 缓存结论

公开接口响应已有禁止缓存头，本次不是 Nginx 或浏览器缓存导致，未增加时间戳参数或轮询遮掩问题。

## 数据处理结论

- 未修改 `published_item_id=88` 的地区字段。
- 未修改任何发布时间或 `expires_at`。
- 未删除数据库或 Docker volume。
- 该历史未分类记录需要管理员通过现有业务地区范围接口重新分类，才能按居民地区公开发现。
