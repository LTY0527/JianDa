# Phase 9.6 真实验收报告

运行日期：2026-08-23（Asia/Shanghai）

## 最终结论

`REAL ACCEPTANCE: PARTIAL`

核心真实门禁已经通过：官方 PDF 67 真实提取并发送 DeepSeek、事实追溯、审核发布；10 个助手真实问题；文档 63 官方真实封面确认与缓存；H5 多视口、大字、图片自然尺寸和搜索转简达。当前只因真机人工体验和两个未注入受保护密码的浏览器场景未完成，保留 PARTIAL，不把跳过项写成 PASS。

## 官方 PDF 67

- 标题：国家卫生健康标准 WS/T 876—2026
- SHA-256：`151e1809c12dc262e4e72b761e1f64f795aa2694f7834acf68149b52236815dd`
- 提取：PyMuPDF，8 页 / 8 segments
- External 成功任务：job 75
- 模型：`deepseek-v4-flash`
- 成功任务 Token：1976；耗时：4538 ms
- 前两次真实失败：job 73（6492 Token）、job 74（2042 Token），保留诊断，不算 PASS
- 事实审核：排除误分类的“发布日期”；确认“实施日期” `2026-09-01`
- 公开 slug：`guide-67`；published item：29
- 用户端：`http://127.0.0.1/guide/guide-67`

job 75 的旧记录因已修复的重写元数据缺陷显示 provider=mock，但运行日志和请求指标证明该次为真实 External。本轮未为改写历史标签额外消耗 External 请求，报告如实记录该异常。

## 助手真实问题集

- 10 个不同问题，覆盖银龄活动、健康体检、文档 67、大场镇开放日、反诈和健康科普。
- 12 次成功 External 回答，成功 Token 合计 8833，耗时 952—1885 ms。
- 4 次真实响应被 Schema/事实安全检查拒绝并回退检索；5 次 422 在 Provider 前失败，均未冒充 External PASS。
- 本轮 PDF 与助手 External 总请求约 20 次，总量低于 25 次与 120000 Token 授权上限。

## 真实图片

- 文档 63 候选 8：官方来源、1949×1183 JPEG。
- SHA-256：`83e7df1405a3f54c3a5747f8e4c72797882f1f467ed6d9fe934eb681d9d857e8`
- 状态：`APPROVED / CONFIRMED`，`image_cached=true`
- 公开 cover：HTTP 200，`image/jpeg`，204151 bytes
- 页面：`http://127.0.0.1/news/news-63`
- 使用范围仅为本地、局域网和课堂 Demo，不主张商业版权授权。

## 自动验收

- AI：105 passed
- Maven：72 passed
- 两端 typecheck/build：全部通过
- 全量 Playwright：105 passed、16 skipped、0 failed
- 一键 REAL Playwright：2 passed、2 skipped
- Docker：四服务 healthy；四健康接口 HTTP 200
- No-Mock guard：PASS
- Phase 7.3 证据：一键脚本运行前后哈希一致

## 剩余风险

- 当前 Codex 进程没有受保护的平台/居民密码，2 个认证 REAL 浏览器场景跳过。
- iPhone Safari、Android Chrome 的触控、朗读、拨号仍需真实设备人工确认。
- 未来公网或商业部署必须重新审核图片版权与缓存策略。

本报告不包含 API Key、JWT、数据库密码、Cookie、Authorization、完整 Prompt 或模型原始响应。
