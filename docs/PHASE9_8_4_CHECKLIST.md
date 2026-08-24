# Phase 9.8_4 最终真实验收清单

状态：`DONE / PARTIAL / FAILED / BLOCKED / SKIPPED`。本轮全部目标 DONE/PASS。

| 顺序 | 任务 | 状态 | 证据 |
| ---: | --- | --- | --- |
| 1 | 现场接管与分支基线 | DONE | feat/phase9-8-core-reliability-v1 @ 182f7cf，4 服务 healthy |
| 2 | PDF 真实材料矩阵全链路（文字/扫描/复杂/JPG/PNG） | DONE | PHASE9_8_4_PDF_FINAL_REPORT.md |
| 3 | PNG 走完整业务链路 OCR→DeepSeek→WAITING_REVIEW | DONE | extraction_method=ocr，provider=external |
| 4 | 图片发现能力增强（og/twitter/JSON-LD/srcset/data-*） | DONE | web_ingest.py |
| 5 | 静态无图页浏览器渲染兜底（低频单页） | DONE | 仅公开可访问页 |
| 6 | 首屏 Hero 优先真实图 + 无图文字卡 | DONE | h5-home-*.png 4 视口 |
| 7 | 首屏多视口截图（375/390/768/1440） | DONE | artifacts/phase9-8-4-final/ |
| 8 | 管理员真实浏览器全链路（26 步） | DONE | 11 张截图 + real spec |
| 9 | 管理员 UI 卡点修复（loading/结果/失败/下一步） | DONE | 人话提示 + 下一步主按钮 |
| 10 | REPORTED 帖子公开互动阻断 | DONE | requirePublicVisiblePost |
| 11 | REPORTED/HIDDEN 公开媒体阻断 | DONE | CommunityMediaService p.status='VISIBLE' |
| 12 | 治理回归（发帖→举报→隐藏→恢复 13 步） | DONE | resident-governance-real.json |
| 13 | Assistant 旧配置清除（三级预算统一） | DONE | 全仓 0 legacy 命中 |
| 14 | Assistant 三级预算边界用例 | DONE | AssistantExternalIntegrationTest |
| 15 | 一键真实验收脚本 | DONE | run_phase9_8_4_real_acceptance.ps1 |
| 16 | 机器可读验收汇总 | DONE | real-acceptance-summary.json 16 PASS |
| 17 | AI pytest 全量 | DONE | 118 passed（含 test_web_ingest 图片发现） |
| 18 | Backend Maven 全量 | DONE | 86 passed / 0 fail / 0 err / 0 skip（20 类），V31 H2 兼容 |
| 19 | 机构端 typecheck/build | DONE | institution-web vue-tsc 0 error；:8090 200 |
| 20 | H5 typecheck/build | DONE | user-h5 vue-tsc 0 error；:80 200 |
| 21 | Playwright 回归 | DONE | core 5 pass/1 skip；phase9-5 4 pass |
| 22 | Playwright REAL（Phase9.8.4） | DONE | phase9-8-4-real-acceptance.spec.ts |
| 23 | 最终报告文档 | DONE | 7 份 docs/PHASE9_8_4_*.md |
| 24 | 中文 Git 提交（不 push） | DONE | 本轮提交 |

## Final Gate

| Gate | 状态 |
| --- | --- |
| AI_REWRITE_REAL_ACCEPTANCE | PASS |
| PDF_REAL_ACCEPTANCE | PASS |
| CRAWL_REAL_ACCEPTANCE | PASS |
| IMAGE_SOURCE_PIPELINE_REAL_ACCEPTANCE | PASS |
| HOME_VISUAL_ACCEPTANCE | PASS |
| ASSISTANT_REAL_ACCEPTANCE | PASS |
| ADMIN_FLOW_REAL_ACCEPTANCE | PASS |
| RESIDENT_REAL_ACCEPTANCE | PASS |

## Remaining

- P0：无
- P1：无（覆盖率与大场数量作为 OBSERVED_METRIC，不阻塞）
- P2：UPLOADED/WAITING_REVIEW 真实候选可继续人工审核发布；不属本轮收口范围。

结论：Phase 9.8_4 收口完成，进入最终人工验收与答辩准备。
