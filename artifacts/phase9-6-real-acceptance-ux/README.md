# Phase 9.6 真实验收视觉证据

本目录只保存 Phase 9.6 明确要求的真实 Docker 验收截图，不使用 `page.route`、`route.fulfill` 或 Mock 数据。

## before

真实配图和产品改造前的 H5 与机构端基线。

## real-e2e

- `03-review.png`：文档 63 机构端审核证据。
- `05-h5-published-real-photo.png`：文档 63 发布后的用户端证据。
- 文档 67 的认证浏览器截图仅在调用进程预先提供 `JIANDA_REAL_PLATFORM_PASSWORD` 时生成；当前运行明确跳过，没有伪造截图。

## after

- `h5-news-63-375-18px.png`
- `h5-news-63-390-20px.png`
- `h5-news-63-768-22px.png`
- `h5-news-63-1440-24px.png`

四张截图均来自真实公开接口。Playwright 同时断言页面无横向滚动，所有可见 `img` 的 `naturalWidth` 和 `naturalHeight` 大于 0。

历史 `artifacts/phase7-3` 不属于本目录；一键脚本会在运行前后比较其哈希，禁止覆盖既有验收证据。
