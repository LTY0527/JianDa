# web-v1.1 Schema 故障与恢复

## 根因

外部模型的事实提取与适老化改写共用一次同步请求。事实提取成功后，若改写响应缺字段、类型错误或 JSON 不合法，AI 服务仅返回普通 503，后端将整项任务标记失败并丢弃已验证事实。后端读超时也短于外部模型的完整重试预算。

## 修复

- Schema 错误使用结构化安全错误：error_code、stage、schema_version、json_path、keyword、短 SHA-256 指纹、request_id、retryable。
- 不记录或返回模型原始响应、正文、Authorization 或 API Key。
- JSON 验证前仅执行 BOM/空白、Markdown fence、短说明包装及 JSON 字符串包装等可审计规范化。
- web-v1.1 请求版本统一用于 Prompt 路由、结构验证与日志，不再只依赖部署默认版本。
- 改写失败时，AI 服务随安全错误返回已验证事实检查点；后端持久化到 processing_job 并保存 extracted_field，不生成 generated_content 半成品。
- 新增 POST /api/documents/{id}/retry-rewrite，仅使用检查点调用 /internal/rewrite，不重复事实提取；禁止发布材料重试并阻止并发重复提交。
- 后端只把白名单结构化诊断转换为中文提示；其他内部或上游异常继续返回通用信息。
- 后端 AI 调用读超时调整为 210 秒，覆盖默认三次 60 秒尝试及退避。

## web-v1.1 契约

改写输出与 RewriteResponse 同步，包含 prompt_version、summary、quick_summary、why_it_matters、action_checklist、key_facts、common_mistakes、faq、terms、scope、uncertainties、plain_text、steps、warnings、term_explanations、audio_script。事实输出使用 FactExtractionResponse。

## 规范化规则

- trim_bom_or_whitespace
- unwrap_markdown_fence
- extract_json_value
- discard_short_explanation
- unwrap_json_string

规范化日志只记录规则、响应长度和指纹。无法确定的业务事实不补全；Schema 验证仍是最终契约。

在 JSON 可解析但与契约存在可安全恢复的结构差异时，Fact 与 Rewrite 阶段还会统一执行：

- 枚举别名归一化，包括字段类型及 deadline、priority、scope；
- 缺失可选字段补充 Schema 定义的安全空值，不补写事实值；
- 顶层和嵌套未知字段隔离，不让未知数据进入正式发布结构；
- 无法识别的事实字段写入 `uncertain_fields`，并合并到最终 `uncertainties`；
- 每次恢复记录字段路径到 `repaired_paths`，日志仍不包含原文或完整模型响应。

歧义、无法验证引用或无法安全恢复的必填结构继续失败，不以默认业务内容伪造成功。

## 阶段检查点

FACT_EXTRACT_SUCCEEDED（fact_checkpoint_json 已持久化）→ REWRITE_PENDING → accessible_rewrite → SUCCEEDED / WAITING_REVIEW。失败时 last_failed_stage、provider_request_id、response fingerprint、事实阶段 token 和耗时保留。

## 测试结果（2026-07-29）

- AI：86 passed；使用本地 Mock HTTP Server 覆盖 Schema 恢复，未用真实内容触发恢复分支。
- 后端：54 tests，0 failures，0 errors；包含事实检查点、仅重试改写、图片候选、助手
  RAG、三段式采集和运营指标集成测试。
- institution-web：typecheck、build 通过。
- user-h5：typecheck、build 通过。
- Playwright：82 passed、9 个受控条件 skip、0 failed。
- Docker：MySQL、AI、Backend、Frontend 均 healthy，四个健康入口 HTTP 200。

## 真实验收

本轮完成的真实调用是 document_id=32 的助手 RAG smoke，不经过 Fact/Rewrite Schema 恢复，
因此不能据此声称真实 Schema 修复分支已经命中。该 smoke 为 deepseek-v4-flash、
prompt_version=v1.1、516 tokens、1725ms、HTTP 200；未自动审核或发布，调用后开关恢复关闭。
真实材料的 Schema 恢复验收仍需保留 processing_job_id、repaired_paths 和 WAITING_REVIEW 现场。

## 提交

提交 SHA 在拆分提交后补充。

## 剩余问题

- 当前有限规范化仍需持续监控多 JSON 对象等歧义包装，遇到歧义应拒绝而不是猜测。
- 事实与引用的语义一致性当前主要依赖连续引用、页码、段落及数字校验；非数字语义蕴含仍需更强验证。
- Playwright、Docker 四健康入口与受控真实 DeepSeek 验收尚待执行。
