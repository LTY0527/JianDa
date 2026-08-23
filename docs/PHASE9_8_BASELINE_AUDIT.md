# Phase 9.8 基线审计

日期：2026-08-23

## Git

- 起始分支：`feat/phase9-7-final-commercial-polish-v1`
- 起始 HEAD：`b30839a`
- 当前分支：`feat/phase9-8-core-reliability-v1`
- `.env` 被 `.gitignore` 忽略且未被 Git 跟踪，未读取或输出其内容。
- 工作区存在 15 张用户已有的 `artifacts/phase7-3` 截图修改；Phase 9.8 不覆盖、不删除、不暂存且不提交这些文件。

## 运行环境

| 服务 | 基线状态 |
| --- | --- |
| MySQL 8.4 | healthy |
| FastAPI AI | healthy |
| Spring Boot | healthy |
| frontend | healthy |

Docker volumes 保留，不执行 `docker compose down -v`。

## 已复现的代码级缺口

1. `extraction.py` 仅在文字完全为空时 OCR，PNG/JPG 直接返回 `manual_required`。
2. 适老化 rewrite 已有部分 Schema 修复和人工“仅重试改写”能力，但 Prompt 没有完整列出 enum，且两次 rewrite 失败后仍会将材料标记 FAILED。
3. 网页发现失败仍需要更精确的错误分类、后台化和可见进度。
4. 真实图片开关和历史回填数据需以 MySQL 实际结果核验，不以 fixture 代替。

## 基线结论

`BASELINE_STATUS: DONE`

从 AI rewrite 可恢复和 PDF/图片提取开始，不扩大到无关功能。

## 2026-08-23 续跑复核（不改写历史基线）

- 当前分支仍为 `feat/phase9-8-core-reliability-v1`，续跑起点 HEAD `62dcd9a`。
- V27–V29 已在真实 MySQL 应用；Docker 四项服务健康。
- 真实环境安全核验为 External provider / `deepseek-v4-flash` 且 Key 已配置；没有输出 Key 值。
- 基线第 1 项已由真实批测修复：每页质量评分、OCR 比较、人工复核和 POOR 文本拒绝均有 Docker/Tesseract 证据。
- 基线第 2–4 项仍以各专项真实验收 Gate 为准；实现存在不等同于最终 PASS。
- 原有 15 张 `artifacts/phase7-3` 用户截图修改继续保持未暂存、未覆盖、未删除。
