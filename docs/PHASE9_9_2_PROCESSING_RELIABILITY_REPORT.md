# Phase 9.9.2 处理可靠性报告

- 新增 `GET /api/documents/{id}/processing-snapshot`，运行时仅返回阶段、进度、心跳、耗时、jobId、错误和结果可用性。
- Processing 页运行中每两秒只请求 snapshot；终态后再读取完整详情、字段和生成内容。
- V36 增加处理心跳。10 分钟无心跳时任务转为 `FAILED_RETRYABLE`、阶段为 `HEARTBEAT_STALE`，文档转为 FAILED。
- Backend 到 AI 改为生命周期级 Java 17 `HttpClient`，统一连接复用和连接/请求超时。

轻量轮询和死任务保护已由 Maven 与 Playwright 覆盖。本轮未获得新的 External 外发授权，未用 DeepSeek 重新消费材料；既有真实 External 结果不被 Mock 替换。
