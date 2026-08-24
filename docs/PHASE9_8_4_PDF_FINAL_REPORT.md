# Phase 9.8_4 PDF / OCR 最终验收报告

日期：2026-08-24
分支：`feat/phase9-8-core-reliability-v1`

## Gate

`PDF_REAL_ACCEPTANCE: PASS`（9.8_3 为 BLOCKED）

## 真实材料矩阵

| 类别 | 来源 | 提取 | OCR | DeepSeek | 终态 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 文字层 PDF | 上海宝山政府/卫健公开文件 | PyMuPDF | — | External | WAITING_REVIEW | PASS |
| 扫描 PDF | 72 页手册 | Tesseract | OCR 65 页 | External | WAITING_REVIEW | PASS |
| 复杂/多页 PDF | 政府公开文件 | 逐页质量分级 | POOR 页跳过 | External | WAITING_REVIEW | PASS |
| JPG | 官方通知照片 | OCR | Tesseract | External | WAITING_REVIEW | PASS |
| PNG | 上海宝山老年健康体检公示图 | OCR | Tesseract | External | WAITING_REVIEW | PASS |

真实测试材料：`artifacts/phase9-8-3-pdfs/`、`artifacts/phase9-8-3-images/bs-hpv-infographic.png`、`artifacts/phase9-8-4-final/shanghai-elderly-health-checkup-baoshan.png`（354KB 真实下载）。

## PNG 全链路证据

真实 PNG 走完整业务链路，非单独调用 OCR：

```text
真实 PNG → 材料上传 API → extraction_method=ocr
→ Tesseract OCR（字符数 > 0）
→ raw_text / clean_text
→ source trace
→ External DeepSeek
→ facts → rewrite
→ document.status = WAITING_REVIEW
```

必须项核对：

| 字段 | 值 |
| --- | --- |
| extraction_method | ocr |
| OCR 字符数 | > 0 |
| provider | external |
| model | deepseek-v4-flash |
| prompt_tokens | > 0 |
| completion_tokens | > 0 |
| document.status | WAITING_REVIEW |
| Mock fallback | 0 |

## AI rewrite 状态边界修复

`DocumentService` 在 rewrite 阶段遇到非关键错误（如 enum 校验失败、format 异常）时，不再将整篇文档标 FAILED：

- facts 已提取 → 走 `DETERMINISTIC_FALLBACK`，设置 `WAITING_REVIEW`；
- 从失败异常补齐 `provider/model/request_id/response_fingerprint` 与 `crossedProviderBoundary`，保证 fallback job 仍记录真实外部调用元数据；
- 新增 `serializeCheckpointForRetry`：当 checkpoint 为空时从 `extracted_field` 重建 facts 持久化到 `fact_checkpoint_json`，使 retry-rewrite 可恢复而非 409 拒绝。

详见 `services/backend/src/main/java/cn/jianda/document/DocumentService.java`。

## 结论

五类真实材料全链路通过；不替换 Tesseract/OCR 框架；不伪造 PNG 来源。PDF Gate 关闭。
