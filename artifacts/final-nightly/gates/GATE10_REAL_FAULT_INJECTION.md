# Gate 10：真实 AI 故障注入

- 结论：PASS
- 操作：停止 `ai-service`，保持 backend、institution-web、user-h5 和 MySQL 运行。
- 观察：用户端已发布 44 项仍可读取；新材料处理明确失败，没有静默切换 Mock。
- 恢复：重启 AI 服务后通过业务重试完成处理，状态进入 `WAITING_REVIEW`。
- 恢复任务：External / `deepseek-v4-flash`，3944 Token，8157 ms。
- 数据安全：未删除 volume，未使用 SQL 篡改业务状态，未输出任何密钥。
