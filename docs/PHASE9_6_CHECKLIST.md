# Phase 9.6 真实全链路验收清单

更新日期：2026-08-23

## P0：真实链路

- [x] 官方 PDF 文档 67 使用 PyMuPDF 提取 8 页、保存 8 segments，并执行真实 External。
- [x] 文档 67 的字段、生成内容、source quote、页码与 segment 完成事实追溯；无关“发布日期”字段通过审核 API 排除。
- [x] 文档 67 完成人工审核与发布，公开 slug 为 `guide-67`，原 PDF 可查看。
- [x] 真实 External 处理没有使用 Mock fallback 冒充成功；失败尝试保留诊断。
- [x] 文档 63 官方图片候选 8 完成来源核对、人工确认、本地缓存和公开 cover 验证。
- [x] 10 个真实助手问题完成 DeepSeek 评估，并对事实与引用执行确定性后检。
- [x] External 请求和 Token 使用保持在用户授权的 25 次/120000 Token 上限内。

## P1：产品能力

- [x] 主图大卡、普通图文、无图紧急通知、精简服务使用四种独立信息结构，未重复创建组件。
- [x] 搜索无结果显示“平台资料暂未命中”，可携带原关键词进入简达助手。
- [x] 审核页可排除无关字段，保留审计历史且不污染公开字段和服务目录。
- [x] 助手检索加入已确认字段；证据上限、日期归一化和高风险事实覆盖检查已修复。
- [x] 图片候选和缓存开关职责分离；未经确认的第三方图片仍不得公开。
- [x] 机构端继续按来源、采集、图片、审核、发布和运营状态展示问题与下一步操作。

## P2：质量与证据

- [x] 375/390/768/1440 与 18/20/22/24px 自动验证完成。
- [x] REAL Playwright 对全部可见真实 `img` 检查自然宽高均大于 0。
- [x] after 多视口截图已写入 `artifacts/phase9-6-real-acceptance-ux/after`。
- [x] 新增 `scripts/run-phase9-6-real-acceptance.ps1`，包含 Docker、健康检查、No-Mock guard、REAL Playwright、可选全量回归和 Phase 7.3 证据保护。
- [x] AI 105、Maven 72、两端 typecheck/build、全量 Playwright 105 passed/16 skipped/0 failed。
- [x] Docker MySQL、AI、backend、frontend 四服务 healthy，四个健康接口 HTTP 200。
- [x] 一键脚本运行前后 Phase 7.3 目录哈希一致；15 张用户已有修改截图未暂存或提交。

## 仍待人工验收

- [ ] iPhone Safari 与 Android Chrome 的真实触控、系统朗读和拨号体验仍需设备侧人工确认。
- [ ] 受保护平台/居民密码未注入当前 Codex 进程，REAL Playwright 中 2 个认证场景明确跳过；对应业务 API 与既有真实浏览器证据已完成，但本轮不冒充浏览器重跑 PASS。
- [ ] 未来公网或商业部署前，真实图片仍需重新进行商业版权审核；本轮确认仅用于本地、局域网和课堂 Demo。

最终结论：`REAL ACCEPTANCE: PARTIAL`。真实 PDF、External 助手、真实封面和公开页面门禁已通过；剩余阻塞仅为真机与受保护认证浏览器复跑。
