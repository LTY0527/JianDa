# 简达验收前最终夜间全链路报告

日期：2026-08-31

分支：`final/pre-acceptance-nightly-20260831`

基线：`ee5dc4fe41b070abdd7a85b5f5e9a97c1997256e`

## 结论

`REAL ACCEPTANCE: PARTIAL`

核心技术链路通过：登录门禁、上传/处理/审核/发布、用户端阅读、真实助手证据约束、三区域隔离、图片失败回退、构建与服务故障恢复均已验证。未判定总 PASS 的原因是顾村当前时效内本地内容仍为 0，且当前机器未配置高德 JS API 凭据；没有使用伪数据或假地图掩盖缺口。

## 自动验证

- AI：127 passed，5 warnings，54.82s。
- Backend：Maven exit 0，110 tests，0 failure/error。
- 机构端：typecheck PASS，production build PASS。
- 用户端：typecheck PASS，production build PASS。
- Playwright：147 passed、31 条条件性跳过、0 failed（178 total）；高德真实地图因缺本机凭据明确跳过。
- Docker：全镜像 build PASS；MySQL、AI、Backend、Frontend 均 healthy，四个健康接口均 HTTP 200。
- 助手：10 个真实问题完成；政府开放日问题最终只引用 `news-63`，虚构补贴和停药问题均为 `NO_EVIDENCE`。
- 性能：公开内容 p95 11ms、服务目录 p95 9ms、管理端文档 p95 24ms、创建支付会话 p95 16ms。

## 修复摘要

- 收紧政府开放日的事实检索边界，避免泛化“安排”文章成为证据。
- 修复 H5 助手内部来源渲染为无 `href` 锚点的问题。
- 建立 Playwright 临时居民登录态并覆盖开发/Docker 源；游客用例保持隔离。
- 对齐当前 Processing、助手、来源运营、封面回退和会员页面契约。
- 图片断言兼容 lazy loading：滚入视口后验证真实解码尺寸。

## 未完成与人工项

- 顾村需通过受控影子采集补充当前高价值本地内容。
- 高德真实地图需提供本地验收凭据后复测；当前明确降级，不渲染假地图。
- iPhone Safari / Android Chrome 的系统语音、拨号器与主观触感仍需设备人工验收。
- Browser plugin 不可用，本轮使用仓库 Playwright Chromium。

未 push，未删除 Docker volume，未输出或提交任何秘密。
