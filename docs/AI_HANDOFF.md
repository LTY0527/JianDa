# 简达 AI 开发交接

## DeepSeek External Provider（2026-07-26）

- 默认仍为 `LLM_PROVIDER=mock`，无需密钥，既有确定性演示流程不变。
- `LLM_PROVIDER=external` 使用 OpenAI-compatible `POST /chat/completions`，默认模型 `deepseek-v4-flash`，允许配置 `deepseek-v4-pro`。
- 阶段 A 仅提取事实，输出字段白名单、`source_quote`、`page_no`、`segment_id` 和置信度；程序再次验证逐字引用与实际段落一致。
- 阶段 B 仅使用已验证事实生成适老化内容，不接收未校验的自由字典。
- 两阶段分别使用严格 Pydantic Schema；非法 JSON、Schema 错误、截断输出和不可追溯引用会明确失败，不会写库，也不会回退 Mock。
- 提示词位于 `services/ai-service/app/prompts/`，当前版本为 `v1`。日志仅记录阶段与版本，不记录完整材料、Authorization、API Key 或 `reasoning_content`。
- 后端把数据库中真实 `document_segment` 的 ID、页码、文本以及机构来源名称传给 AI 服务，保存前仍保留后端二次引用定位。
- 自动测试只使用本地 mock HTTP Server，尚未执行任何真实 DeepSeek 请求。

真实联调前复制 `.env.example` 到本机 `.env`，至少设置 `LLM_PROVIDER=external` 和 `EXTERNAL_LLM_API_KEY`，并完成数据合规确认。不要把 `.env` 或真实密钥提交到 Git。

更新时间：2026-07-26

## 当前状态

Phase 8.2A Docker Compose 启动修复已完成。当前 `main` 分支的 Compose 栈包含：

- `mysql`：healthy，宿主机默认端口 3307；
- `ai-service`：healthy，`MockProvider`；
- `backend`：healthy，prod profile、MySQL、Actuator；
- `frontend`：healthy，Nginx 同时托管 H5 和机构端。

用户端为 `http://127.0.0.1`，机构端为 `http://127.0.0.1:8090`。

## 本次根因

`FixtureCollector` 原先默认读取相对文件路径 `../../fixtures/public-information.json`。容器工作目录为 `/app`，后端镜像只包含 JAR，仓库根目录 fixture 没有进入 JAR 或运行镜像，因此 prod 启动解析为 `/fixtures/public-information.json` 并抛出缺失异常。

## 修复方案

- 将 fixture 放入 `services/backend/src/main/resources/fixtures/public-information.json`。
- `FixtureCollector` 未配置外部路径时读取 classpath 默认资源。
- 可通过 `JIANDA_PUBLIC_FIXTURE` 显式指定外部文件。
- 显式外部路径缺失时保留启动失败，并在错误中显示规范化后的目标路径。
- fixture 是平台管理员的离线导入能力，不仅是一次性初始化数据，因此保留为正式内置资源。

## 修改文件

- `.env.example`
- `docker-compose.yml`
- `services/backend/src/main/java/cn/jianda/collector/FixtureCollector.java`
- `services/backend/src/main/resources/application.yml`
- `services/backend/src/main/resources/fixtures/public-information.json`
- `services/backend/src/test/java/cn/jianda/collector/FixtureCollectorTest.java`
- `README.md`
- `docs/DEPLOYMENT.md`
- `docs/TASKS.md`
- `docs/AI_HANDOFF.md`

## 验证证据

```powershell
mvn -f services/backend/pom.xml test
mvn -f services/backend/pom.xml package
& "C:\Program Files\Java\jdk-17\bin\jar.exe" tf services/backend/target/backend-0.1.0.jar
docker compose build backend
docker compose up -d
docker compose ps --all
docker compose logs --no-color --tail=200 backend
$env:JIANDA_DOCKER_COMPOSE_UP = "1"
npx playwright test tests/e2e/phase8-2.spec.ts
```

结果：

- 后端测试：16 项通过，0 失败；
- Maven package：成功；
- JAR：包含 `BOOT-INF/classes/fixtures/public-information.json`；
- 容器接口：`/api/public-sources/fixtures` 返回三条稳定样例；
- Playwright：8/8 通过；
- AI、后端、H5 Nginx、机构端 Nginx 四个健康地址均为 HTTP 200。

首次完整 `docker compose up -d` 在重建后端时出现一次到 MySQL 的瞬时连接超时；同一网络的一次性探针确认 DNS/TCP 正常，随后仅重启 backend 即成功，最终再次执行完整 `docker compose up -d` 时四服务保持 healthy。若冷启动时重复出现，应继续检查 Docker Desktop 网络与 MySQL ready 时序；不要删除 volume 作为常规处理。

## 尚待人工验收

- iPhone Safari 与 Android Chrome 的真实设备流程；
- 目标老年用户的字号、导航和语音体验；
- 医疗、反诈、政策内容的业务签字确认；
- Phase 8.3 的 TLS、正式域名、内部端口收敛、非 root 容器、监控告警；
- MySQL 与上传卷的备份及恢复演练；
- 生产演示账号和演示数据策略。

不要执行 `docker compose down -v`，除非已明确批准删除 MySQL 与上传数据。
