# Phase 9.6 真实图片审计

审计日期：2026-08-23

## 最终结论

文档 63 的官方文章图片候选 8 已按本地、局域网和课堂 Demo 授权完成来源核对、技术校验、人工确认与本地缓存。公开端通过同源 cover API 返回真实 JPEG；未审核图片仍不会公开，其他没有合适原图的内容继续使用文字卡或分类默认图。

## 已确认封面

- 文档 ID：63
- 公开 slug：`news-63`
- 类型：`ARTICLE_IMAGE`
- 来源：宝山区政府信息公开·大场镇
- 来源页：`https://xxgk.shbsq.gov.cn/article.html?infoid=6513958d-e52a-41d5-90a1-c8f47c24bf1f`
- 图片尺寸：1949 × 1183
- MIME：`image/jpeg`
- SHA-256：`83e7df1405a3f54c3a5747f8e4c72797882f1f467ed6d9fe934eb681d9d857e8`
- 审核状态：`APPROVED / CONFIRMED`
- 缓存状态：`image_cached=true`
- 公开接口：`/api/public/items/news-63/cover`，HTTP 200，`image/jpeg`，204151 bytes

图片内容为官方“上海政府开放月 / 阳光政务·公开为民”主题图，与文章直接相关，不是随机网络图或 AI 生成图。使用依据仅覆盖本地、局域网和课堂演示，不声称取得商业版权授权。

## 链路修复

- `allow_image_candidates` 允许下载少量数据完成技术校验并生成机构端候选。
- `allow_image_cache` / `image_cache_allowed` 只控制人工确认来源和使用依据后的公开缓存。
- 修复 MySQL 布尔表达式返回数值 `1` 时未被识别为 true 的问题。
- 修复 AI 图片下载路径缺少 8 MiB 最大体积常量的问题。
- 候选缓存成功后同步 `image_cached` 与 hash，公开端只读取已确认缓存。
- 解析继续通用支持 OpenGraph、JSON-LD、src、懒加载属性、srcset、picture/source 与 video poster；没有站点专用选择器。

## 展示验证

- `news-63` 在 375/390/768/1440 下全部可见且不变形。
- 18/20/22/24px 四档字号无横向滚动或文字图片重叠。
- REAL Playwright 对页面所有可见 `img` 断言 `naturalWidth > 0`、`naturalHeight > 0`。
- 图片失败和无图文章仍安全回退，不把分类 SVG 冒充真实 Hero。

## 尚未完成

- 历史 WEB_ARTICLE 已执行安全重扫；现有新华网文章没有通过“属于本文 + 尺寸/MIME/比例”门禁的有效图片，未从搜索引擎或推荐区补图。
- 真实封面覆盖率仍不是 100%；后续按文章逐篇核对，不以无关图片追求覆盖率。
