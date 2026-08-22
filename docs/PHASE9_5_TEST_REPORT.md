# Phase 9.5 测试报告

测试日期：2026-08-23。

## 自动测试

- AI：`97 passed, 6 warnings`；首次从仓库根目录运行因模块路径错误未收集，改在 `services/ai-service` 使用项目虚拟环境后通过。
- 后端：`67 passed, 0 failed`。
- 机构端：typecheck 与生产 build 通过。
- 用户端：typecheck 与生产 build 通过。
- Phase 9.5 定向 Playwright：后台减法 2、来源运营 3、审核发布 3、居民服务 3、社区治理 2、视觉证据 3，均通过。
- 完整 Playwright：`105 passed, 11 skipped`，共发现 116 项；跳过项为开发 fixture、真实 External 或写业务数据等必须显式开启的场景。

## Docker

- `docker compose build` 成功构建 AI、backend、frontend。
- MySQL、AI、backend、frontend 均为 healthy。
- AI `/health`、backend `/actuator/health`、机构端 `/health`、H5 `/health` 均 HTTP 200。
- Flyway 校验 25 个 migration，并在现有 volume 上从 V21 连续迁移至 V25；没有删除 volume。

## 浏览器验收

Browser plugin not available; used repository Playwright.

375、390、768、1440 宽度均完成渲染与溢出检查。移动居民登录曾因负边距产生 4px 横向溢出，已修复并实测六个主页面 `scrollWidth === innerWidth`。截图保存在 `artifacts/phase9-5-commercial-ux/after`。

## 未执行

- 未调用真实 DeepSeek。
- 未执行 git push。
- 未删除 Docker volumes。
