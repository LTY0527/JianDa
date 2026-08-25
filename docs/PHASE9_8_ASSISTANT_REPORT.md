# Phase 9.8 Assistant 真实验收

日期：2026-08-24

## 环境与执行

- Docker AI 配置：`LLM_PROVIDER=external`。
- 真实文档处理历史中存在 `provider_id=external`、`model_id=deepseek-v4-flash`、正数 Token 和真实 provider boundary 记录。
- 本轮显式运行真实 Playwright：`tests/e2e/real/phase9-7-assistant-external.spec.ts`。

## Playwright 结果

- 高风险无依据拒答：PASS（1 项）。
- 上下文问答 + 通用问题组合：FAILED（1 项）。
- 失败断言：6 个 grounded 问题全部进入明确 retrieval fallback，`aiCount=0`，要求至少 4。

MySQL 对应真实事件：

- grounded 请求均保留 citation，但 `error_code=BUDGET_LIMIT`。
- 当日 Assistant AI 使用为 30 calls、22,486 tokens，已触发应用内日预算门禁。
- 高风险虚构电话/金额请求以 `NO_EVIDENCE` 安全拒答，未调用 External。

## 判定

- 这是安全降级而非 Mock 冒充：响应明确为 retrieval，Token 为 0，原因可审计为 BUDGET_LIMIT。
- 但真实 External Assistant 本轮没有成功 HTTP/Token 证据，不能用历史 External 文档处理记录替代 Assistant 门禁。

`ASSISTANT_REAL_ACCEPTANCE: BLOCKED`

阻塞原因：当日真实 Assistant 调用预算已耗尽。没有清空历史事件、调高预算、绕过门禁或伪造成功。
