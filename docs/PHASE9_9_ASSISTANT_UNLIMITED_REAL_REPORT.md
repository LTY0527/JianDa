# Phase 9.9 助手持续使用验收

## 已验证

- H5 会在居民登录后携带 `X-Resident-Token`；后端记录 `identity_type=RESIDENT` 对应的居民 ID，不记录原始 Token。
- 每日 call/token 配置不再作为居民、游客或全局业务阻断；调用次数和 Token 继续用于运营统计。
- 后端连续 30 次居民请求集成测试通过，第 30 次没有出现“今日额度已用完”。
- 保留单请求最大 Token、超时、重试、并发保护、外部服务熔断和高风险无依据拒答。
- 修复了 External 关闭但已发布内容检索成功时遗漏 `assistant_query_event` 的统计问题；完整 Maven 89 项通过。

## External 边界

本轮没有新的 External Provider 数据发送授权，因此未调用 DeepSeek，也没有输出或读取密钥。30 问真实 External 内容质量验收不得用 Mock、H2 或纯检索结果冒充。

## 结论

- 居民身份与无每日额度阻断：`PASS`
- 30 次本地持续调用门禁：`PASS`
- 30 问 DeepSeek REAL 质量验收：`BLOCKED_BY_AUTHORIZATION`
- `ASSISTANT_UNLIMITED_REAL_ACCEPTANCE=PARTIAL`
