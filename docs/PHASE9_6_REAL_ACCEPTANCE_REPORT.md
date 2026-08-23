# Phase 9.6 真实验收报告

运行日期：2026-08-23（Asia/Shanghai）

## 结论

`REAL ACCEPTANCE: PARTIAL`

真实大场镇网页、发现、影子采集、材料创建、AI 审批队列、External DeepSeek、事实审核、发布、H5、scheduler、居民邻里、提醒和运营指标均已形成真实证据。以下门禁仍未满足，因此不能写 PASS：

- 官方 PDF 已上传并由 PyMuPDF 提取，但未获得把该 PDF 正文发送给 External Provider 的单独授权；External 阶段为 BLOCKED。
- 大场镇文章存在技术有效的真实图片候选，但没有确认公开缓存和使用依据；候选已拒绝，公开端仍为分类默认图，真实封面门禁失败。
- 助手已完成安全升级与自动回归，但真实 DeepSeek 助手 10+ 问题集未获本轮外部数据调用授权。
- after 多视口完整截图集与 iPhone/Android 人工验收未完成。

## 运行基线

- branch：`feat/phase9-6-real-acceptance-v1`
- tested HEAD：`5044ca4`
- Docker：MySQL、AI、backend、frontend 均 healthy
- DB schema：Flyway V26，真实 MySQL volume
- 健康接口：8001、8080、8090、80 均 HTTP 200

## 真实网页与 DeepSeek

- source registry：5，宝山区政府信息公开·大场镇
- URL：`https://xxgk.shbsq.gov.cn/article.html?infoid=6513958d-e52a-41d5-90a1-c8f47c24bf1f`
- robots：NOT_FOUND_ALLOW
- documentId：63；jobId：72；queueId：19；slug：`news-63`
- provider：external；model：deepseek-v4-flash；HTTP：成功；Mock fallback：否
- prompt version：web-v1.1；schema：1.1
- tokens：2501 + 1576 = 4077；elapsed：10318 ms
- regionCode：310113102；local scope：STREET
- 字段追溯：segment 105；字段与生成内容经自动化事实断言和人工修正 API 核对
- H5：`http://127.0.0.1/news/news-63`

## 官方 PDF

- documentId：67；标题：国家卫生健康标准 WS/T 876—2026
- 本地官方文件 SHA-256：`151e1809c12dc262e4e72b761e1f64f795aa2694f7834acf68149b52236815dd`
- 页数/segments：8/8；extraction：pymupdf
- 当前状态：UPLOADED；fields/generated：0/0
- External：未调用；`BLOCKED: explicit PDF external-data authorization required`
- 原始文件已保留，未删除 volume，未改写数据库制造结果。

## 图片

- 发布总数：22；网页文章：5；真实封面：0；分类默认图：22。
- 文档 63 候选 8 通过技术与相关性检查，但权利依据未确认，已拒绝。
- Hero：没有合法真实图时使用文字主视觉，不用默认 SVG 冒充真实照片。

## 居民、邻里与运营

- 真实 DEMO 居民：resident id 1；真实 BCrypt/session/MySQL。
- 验收帖子：post id 4；创建、列表、点赞、评论、举报、管理员隐藏、居民端不可见、管理员恢复全部成功。
- 发布内容：published item id 28（`news-63`）。
- 指标增长：周阅读 63→64、周收听 0→1、周提醒 0→1；同时记录电话点击和地址复制事件。
- 新增无 Mock Playwright 真实套件再次完成同一闭环，1/1 通过。

## Browser 证据

- 真实发布：`tests/e2e/phase9-6-real-published.spec.ts`，1/1 passed。
- 真实居民运营：`tests/e2e/real/phase9-6-resident-operations.spec.ts`，1/1 passed。
- REAL 守卫禁止 `page.route`、`route.fulfill`、MockProvider 和 fixture 标记。
- Browser plugin unavailable；按仓库 Playwright fallback 执行。

本报告不包含 API Key、JWT、密码、Authorization、完整 Prompt 或模型原始响应。
