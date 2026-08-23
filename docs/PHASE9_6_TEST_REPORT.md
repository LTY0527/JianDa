# Phase 9.6 测试报告

日期：2026-08-23

## Regression Tests

| 检查 | 结果 |
| --- | --- |
| AI pytest | 101 passed，6 warnings；warning 为 SWIG deprecation 与沙箱拒绝 pytest cache 写入 |
| Maven | 71 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS |
| 机构端 typecheck | PASS |
| 机构端 production build | PASS；沙箱内首次被 esbuild `spawn EPERM` 阻止，沙箱外复跑成功 |
| H5 typecheck | PASS |
| H5 production build | PASS；沙箱内首次被 esbuild `spawn EPERM` 阻止，沙箱外复跑成功 |
| 助手隔离 Playwright | 3 passed |
| 全量 Playwright（默认安全条件） | 105 passed，13 skipped，0 failed，118 total |

全量 Playwright 前后，15 张用户现有 `artifacts/phase7-3` 修改截图的组合 SHA-256 均为 `f7d164722df6d2e3a930fbb4c3d73311502adff271ccba161d6af1453a9e0b35`，未覆盖、移动或暂存。

## REAL ACCEPTANCE Tests

| 检查 | 结果 |
| --- | --- |
| 大场镇真实发布 Docker Browser | 1 passed，0 mock |
| 居民/邻里/提醒/运营真实 Docker Browser/API | 1 passed，0 mock |
| REAL suite 静态守卫 | PASS |
| Docker Compose build backend/frontend | PASS |
| Docker 四服务 | 4 healthy |
| 四个健康接口 | 4 × HTTP 200 |

真实 External 网页处理属于真实验收；隔离 Mock、fixture 和被安全条件 skip 的测试不计入 REAL PASS 数量。官方 PDF External、真实来源封面和真实 External 助手问题集仍为阻塞项，最终结论为 PARTIAL。
