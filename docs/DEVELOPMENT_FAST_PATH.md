# 开发快速验证路径

## 日常修改

在仓库根目录运行：

```powershell
.\scripts\verify-fast.ps1
```

快速验证只运行 Phase 9.4 助手/运营相关测试、两个前端 typecheck 和
`git diff --check`。它不会构建 Docker、调用真实模型或写入历史验收截图。

按修改目录追加验证：

- `services/ai-service/`：在该目录用 `.venv\Scripts\python.exe -m pytest tests -q`。
- `services/backend/`：运行 `mvn -f services/backend/pom.xml test`。
- `apps/institution-web/`：运行 typecheck；页面或构建配置变化时再运行 build。
- `apps/user-h5/`：运行 typecheck 和对应 Playwright spec；页面或构建配置变化时再运行 build。
- `tests/e2e/`：优先运行改动对应 spec，里程碑收口时再执行全量 Playwright。
- `docker-compose.yml`、Dockerfile 或 Nginx：执行完整 Docker build 和四项健康检查。

## 里程碑验证

```powershell
.\scripts\verify-full.ps1
```

完整脚本执行 AI 全套、Maven 全套、两个生产构建、全量 Playwright、
Docker 构建/启动和四项 HTTP 健康检查。它不会执行 `down -v`，不会调用真实
DeepSeek，也不会自动审核或发布内容。

## 提高诊断效率

- 搜索时排除 `node_modules`、`dist`、`target`、`.venv` 和 Playwright 临时结果。
- 长日志只查看失败附近或末尾，不把完整响应、请求头或环境变量写入日志。
- 每个独立功能先做定向测试；多个功能形成里程碑后再做全量验证。
- 真实 DeepSeek 仅在自动测试全部通过、得到明确授权并设置调用上限后执行。
