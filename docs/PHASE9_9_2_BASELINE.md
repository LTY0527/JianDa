# Phase 9.9.2 基线

日期：2026-08-25

分支：`feat/phase9-9-commercial-regional-v1`

基线提交：`877de2b`

人工验收暴露了地域串区、假地图、批量导入假失败、处理页高频轮询、会员入口缺失和商业后台工程化文案等问题。本轮在保留真实 MySQL volume、历史截图和未提交现场的前提下修复，不恢复已撤回的演示数据。

开始时四个应用服务均可由 Docker 启动；高德 JS API、Web Search 和真实支付商户凭据未配置。本轮没有新的公开数据 External 外发授权，因此未执行 30 问 DeepSeek。以上缺口不能使用 Mock 冒充通过。

性能基线见 `PHASE9_9_2_PERFORMANCE_BASELINE.md`，数据审计见 `PHASE9_9_2_REAL_CONTENT_AUDIT.md`。
