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

## 阶段检查点

FACT_EXTRACT_SUCCEEDED（fact_checkpoint_json 已持久化）→ REWRITE_PENDING → accessible_rewrite → SUCCEEDED / WAITING_REVIEW。失败时 last_failed_stage、provider_request_id、response fingerprint、事实阶段 token 和耗时保留。

## 测试结果（2026-07-29）

- AI：62 passed；使用本地 Mock HTTP Server，未调用真实 DeepSeek。
- 后端：24 tests，0 failures，0 errors；包含事实检查点和仅重试改写集成测试。
- institution-web：typecheck、build 通过。
- user-h5：typecheck、build 通过。
- Playwright、Docker、真实 DeepSeek：待本阶段提交前的运行环境验证。

## 真实验收

尚未调用真实 DeepSeek；document_id、processing_job_id、token、耗时和 WAITING_REVIEW 结果待受控验收后填写。未审核、未发布。

## 提交

提交 SHA 在拆分提交后补充。

## 剩余问题

- 当前有限规范化仍需持续监控多 JSON 对象等歧义包装，遇到歧义应拒绝而不是猜测。
- 事实与引用的语义一致性当前主要依赖连续引用、页码、段落及数字校验；非数字语义蕴含仍需更强验证。
- Playwright、Docker 四健康入口与受控真实 DeepSeek 验收尚待执行。
