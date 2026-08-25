# Phase 9.6 测试报告

日期：2026-08-23

## 自动回归

| 检查 | 结果 |
| --- | --- |
| AI pytest（从服务目录） | 105 passed，6 warnings |
| Maven | 72 tests，0 failures，0 errors，BUILD SUCCESS |
| 机构端 typecheck | PASS |
| 机构端 production build | PASS |
| H5 typecheck | PASS |
| H5 production build | PASS |
| 全量 Playwright（Docker 测试 URL） | 105 passed，16 skipped，0 failed，121 total |
| PowerShell 一键脚本语法 | PASS |

AI pytest 首次从仓库根执行时因 `app` 包解析目录错误产生 6 个收集错误；切换到 `services/ai-service` 后 105 项全部通过，一键脚本已同步修复执行目录。两端 build 在沙箱内首次被 esbuild `spawn EPERM` 阻止，沙箱外相同命令均成功。

全量 Playwright 首次未设置测试 URL，默认访问未启动的 Vite 5173/5174；在第 9 项中止后，使用仓库支持的 `JIANDA_H5_TEST_URL=http://127.0.0.1` 和 `JIANDA_INSTITUTION_TEST_URL=http://127.0.0.1:8090` 重跑通过。

## REAL ACCEPTANCE

| 检查 | 结果 |
| --- | --- |
| No-Mock 静态守卫 | PASS |
| 文档 67 PDF → External → 审核 → 发布 | PASS（业务 API、数据库与公开端） |
| 真实 DeepSeek 助手问题集 | PASS，10 个不同问题、12 次成功回答 |
| 文档 63 真实官方封面 | PASS，JPEG 1949×1183，公开 cover HTTP 200 |
| 多视口/四档字号/自然图片尺寸 | PASS |
| 搜索无结果 → 带关键词问简达 | PASS |
| 一键 REAL Playwright | 2 passed，2 skipped |
| Phase 7.3 历史证据保护 | 本次脚本 before/after 哈希一致 |

2 个跳过项分别需要受保护的平台或居民密码。密码没有写入 argv、日志或仓库；跳过未计为真实 PASS。

## Docker

- `jianda-mysql-1`：healthy
- `jianda-ai-service-1`：healthy
- `jianda-backend-1`：healthy
- `jianda-frontend-1`：healthy
- 8001、8080、8090、80 四个健康接口均为 HTTP 200。

Browser plugin 当前不可用，按前端测试调试规范使用仓库 Playwright。尚未执行真实 iPhone Safari / Android Chrome 人工验收。
