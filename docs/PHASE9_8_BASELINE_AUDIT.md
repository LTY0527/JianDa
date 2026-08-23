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
