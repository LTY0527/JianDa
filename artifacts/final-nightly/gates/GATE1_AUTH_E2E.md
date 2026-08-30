# Gate 1：居民登录门禁 E2E

- 结论：PASS
- 验证范围：受保护深链回跳、刷新保持、退出后保护、开发端与 Docker 用户端跨源存储态。
- 修复：Playwright 全局准备一次真实 DEMO 居民会话；显式游客用例使用空存储态；产生新会话的测试同步临时 storage state。
- 安全：临时状态仅写入系统临时目录，不包含在 Git 产物中；测试输出不记录密码或完整令牌。
- 浏览器：Browser plugin 不可用，使用仓库 Playwright Chromium。

最终以本轮全量 Playwright 结果为准。
