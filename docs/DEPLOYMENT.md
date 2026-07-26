# Phase 8.1 部署基线

本文描述当前后端、AI 服务和 MySQL 的容器部署基线。它用于部署准备和环境验证，不代表公网生产验收完成。两个 Vue 前端仍需单独构建和托管，Nginx、TLS、正式域名、备份恢复演练和真机验收不在本阶段范围内。

## 当前拓扑

`docker compose` 启动：

- MySQL 8.4，数据保存在 `jianda_mysql` named volume；
- FastAPI AI 服务，默认使用确定性的 `MockProvider`；
- Spring Boot 后端，使用 `prod` profile 和 MySQL，上传文件保存在 `jianda_uploads` named volume。

后端仅在 MySQL 和 AI 服务健康后启动。当前仍会映射 3306、8001 和 8080 到宿主机，适合本地或受控环境验证；公网部署应通过防火墙或后续反向代理只暴露必要入口。

## 环境变量

从 `.env.example` 创建本机 `.env` 后，至少替换：

- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `DB_PASSWORD`
- `JWT_SECRET`
- `JIANDA_CORS_ALLOWED_ORIGINS`

生产后端还要求以下变量存在：

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET`
- `UPLOAD_DIR`
- `AI_SERVICE_URL`
- `JIANDA_CORS_ALLOWED_ORIGINS`

`application-prod.yml` 不为这些值提供开发兜底。Compose 为本地验证保留显式开发默认值，但生产环境必须通过 `.env`、编排平台 Secret 或等价机制覆盖，且不得提交真实值。

CORS 只能使用逗号分隔的明确来源，不能使用 `*`。前端 `VITE_*` 变量在构建时生效，不是容器启动后的动态配置。

## 启动与健康检查

```powershell
Copy-Item .env.example .env
# 编辑 .env，替换密码、JWT 和允许来源
docker compose config
docker compose up --build -d
docker compose ps
```

健康端点：

- AI：`http://127.0.0.1:8001/health`
- 后端：`http://127.0.0.1:8080/actuator/health`

```powershell
curl.exe -f http://127.0.0.1:8001/health
curl.exe -f http://127.0.0.1:8080/actuator/health
```

后端 Actuator 只暴露 health，响应不显示组件详情。业务 Swagger 地址仍为 `http://127.0.0.1:8080/swagger-ui/index.html`；是否在正式环境暴露需要在后续入口层明确决定。

停止服务：

```powershell
docker compose down
```

不要在仍需数据时使用 `docker compose down -v`，该参数会删除 MySQL 与上传 named volumes。

## 数据、迁移与恢复

Flyway 在后端启动时执行数据库迁移。正式发布前必须：

1. 同时备份 MySQL 和上传卷；二者共同构成完整业务数据。
2. 在隔离环境恢复备份并运行 Flyway，确认版本和数据量。
3. 将回滚定义为恢复已验证备份或前向修复，不手工删除已执行迁移。
4. 保持上传卷挂载路径为 `/app/uploads`；数据库保存了容器内文件路径，随意改变挂载路径会导致历史文件不可读。

本阶段只建立持久化基线，尚未提供自动备份、保留周期、RPO/RTO 或灾难恢复脚本。

## AI 服务边界

当前可部署路径使用 `LLM_PROVIDER=mock`。`external` provider 仍是未实现扩展点，不能据此宣称真实模型已经生产化。AI 的 `/internal/*` 接口当前没有应用级服务认证；公网部署前应保持 AI 服务在私有网络，并停止直接映射 8001，或增加后端到 AI 的服务认证。

## 尚未完成的上线门禁

- 两个 Vue 前端的生产构建、静态托管和 SPA fallback；
- 同源 `/api` 反向代理、正式域名、TLS 和安全响应头；
- MySQL 与上传卷备份/恢复演练；
- 非 root 容器、资源限制、日志轮转和监控告警；
- 演示账号和演示数据的生产禁用/初始化方案；
- 真实外部模型集成与数据合规审批；
- iPhone Safari、Android Chrome 和目标老年用户人工抽检；
- 医疗、反诈和政策文案的业务签字确认。

完成上述门禁前，只能表述为“Phase 8 部署基线已建立”，不能表述为“生产上线验收通过”。

## Phase 8.2 前端生产构建与 Nginx 托管

`Dockerfile.frontend` 使用 Node 20 多阶段构建两个 Vue workspace，再由一个 Nginx 镜像提供静态资源：

- 用户 H5 默认映射到宿主机 80 端口；
- 机构端默认映射到宿主机 8090 端口；
- 两个前端都通过同源 `/api/` 反向代理访问 `backend:8080`；
- Vue Router 使用 history 模式，Nginx 通过 `try_files $uri $uri/ /index.html` 支持直接访问和刷新深层路由；
- `/assets/` 下的 Vite 哈希资源使用一年不可变缓存；
- 上传入口设置 `client_max_body_size 25m`，覆盖 Spring Boot 的 21MB 请求上限；
- HTML 响应包含 `X-Frame-Options`、`X-Content-Type-Options` 和 `Referrer-Policy`；
- 两个端口都在 `/health` 返回 `200 ok`。

机构端和 H5 均默认使用同源 `/api`。本地 Vite 开发由各自 `vite.config.ts` 将 `/api` 转发到 `VITE_PROXY_TARGET`，生产构建无需写入固定后端 IP。

### 启动完整栈

```powershell
docker compose up --build -d
docker compose ps
curl.exe -f http://127.0.0.1/health
curl.exe -f http://127.0.0.1:8090/health
curl.exe -f http://127.0.0.1/api/public/items
curl.exe -f http://127.0.0.1:8090/api/public/items
```

可通过 `.env` 中的 `H5_PORT` 和 `INSTITUTION_PORT` 覆盖宿主机端口。生产环境还必须把 `JIANDA_CORS_ALLOWED_ORIGINS` 替换为真实部署来源。

### 自动化验证

无 Docker 环境也可以验证构建产物和部署配置：

```powershell
npm run typecheck
npm run build
npx playwright test tests/e2e/phase8-2.spec.ts
```

容器启动后再执行 Nginx smoke：

```powershell
$env:JIANDA_DOCKER_COMPOSE_UP = "1"
npx playwright test tests/e2e/phase8-2.spec.ts
```

未设置 `JIANDA_DOCKER_COMPOSE_UP=1` 时，容器 smoke 组会跳过，不代表 Nginx 实际运行已经验证。

### Phase 8.3 待办

- 移除或限制 MySQL 3306、AI 8001 和后端 8080 的宿主机映射；当前为开发和既有验收流程保留；
- TLS 终止、正式域名和 CSP；
- 非 root 容器、资源限制、日志轮转与监控告警；
- MySQL 与上传卷自动备份和实际恢复演练；
- 生产演示账号/数据初始化策略和真实外部模型接入。
