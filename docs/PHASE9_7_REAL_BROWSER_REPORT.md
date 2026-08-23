# Phase 9.7 REAL Browser 报告

## 环境

- 浏览器：仓库 Playwright Chromium（Browser plugin 未安装）。
- 地址：H5 `http://127.0.0.1`，机构端 `http://127.0.0.1:8090`。
- 服务：Docker frontend/backend/ai-service/mysql，MySQL 真实数据。
- 密码：只由测试进程环境传入，未写入 argv、日志、截图或 Git。

## 结果

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 平台管理员表单登录、五个一级页、退出 | PASS | `platform-login.png` |
| 居民表单登录、资料、退出 | PASS | `resident-login.png` |
| H5 频道恢复且不重复请求 | PASS | REAL spec |
| 真实图片 naturalWidth/naturalHeight | PASS | 4 视口 REAL spec |
| 18/20/22/24px | PASS | 375px REAL spec |
| 无依据电话/金额诱导 | PASS | 0 引用，不猜测 |
| 大场镇官网 discover | BLOCKED | 官方请求超出验收窗口 |

完整 Playwright 回归：`110 passed / 20 skipped / 0 failed`。20 项 skip 为显式授权、环境或专项捕获门控；Phase 9.7 平台管理员与居民认证用例均实际执行，认证 skip=0。全量结果同时输出 `PHASE7_3_ARTIFACTS_UNCHANGED`。
