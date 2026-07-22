# 简达

基于人工智能的公共服务信息适老化生成与阅读平台。课程版本提供机构端 Web、用户移动 H5、Spring Boot 后端和 FastAPI AI 服务，并保证在无 Docker、无真实模型 Key 时使用 H2 + MockProvider 完整演示。

## 目录

```text
apps/institution-web   Vue 3 机构与平台运营端（5173）
apps/user-h5           Vue 3 移动阅读端（5174）
services/backend       Spring Boot 3 / Java 17（8080）
services/ai-service    FastAPI / Python（8001）
packages               共享类型与设计 token
fixtures               离线权威资讯样例
docs                   需求、架构、API、任务与 UI 文档
scripts                一键开发脚本
```

## 环境

- Node.js 20+ 与 npm 10+
- Java 17、Maven 3.9+
- Python 3.11+
- Docker 可选；本地开发默认 H2，不需要 MySQL

## 首次安装

### PowerShell

```powershell
npm install
cd services\ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
cd ..\..
```

### Bash

```bash
npm install
cd services/ai-service
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
cd ../..
```

## 启动

PowerShell 可运行 `./scripts/dev.ps1`，Bash 可运行 `./scripts/dev.sh`。也可以打开四个终端分别运行：

```powershell
cd services/ai-service; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8001
cd services/backend; mvn spring-boot:run
npm run dev:institution
npm run dev:h5
```

地址：机构端 `http://localhost:5173`，用户端 `http://localhost:5174`，Swagger `http://localhost:8080/swagger-ui.html`，AI 文档 `http://localhost:8001/docs`。

## 演示账号

以下账号仅用于本地演示，密码均为 `Jianda@123`：

- `platform_admin`：平台管理员，简达平台运营中心
- `org_admin`：机构管理员，浦江街道社区服务中心
- `reviewer`：审核员，浦江街道社区服务中心

生产部署必须替换演示账号和 `JWT_SECRET`。

## 核心演示流程

1. 启动 AI、后端和两个前端。
2. 使用 `org_admin / Jianda@123` 登录机构端。
3. 进入“材料管理 → 上传材料”，选择 PDF/PNG/JPG。
4. 系统保存原始文件、调用稳定 MockProvider，并生成字段、通俗版和步骤卡片。
5. 在左右对照页修改并确认字段，完成审核。
6. 设置分类和来源后发布。
7. 打开用户 H5，查看刚发布的内容，切换 18/20/22/24px 字号，测试朗读、收藏与原文。

## 测试

```powershell
npm run typecheck
npm run build
cd services\backend; mvn test; mvn package
cd ..\ai-service; .\.venv\Scripts\python.exe -m pytest -q
```

## 数据与安全

开发默认数据保存在 `services/backend/data` 的 H2 文件和根目录 `uploads/`。正式配置使用 MySQL 8 与同一套 Flyway 表结构。密码使用 BCrypt；JWT 密钥来自环境变量；上传限制为 20MB 且只接受 PDF/PNG/JPG，文件名会清理并使用随机存储名防止路径穿越。

## Docker（可选）

安装 Docker 后运行 `docker compose up --build`，会启动 MySQL 8、AI 服务和后端；两个 Vite 前端仍按上面的 npm 命令启动。

## 当前限制

- OCR 未安装时，图片材料使用手工正文/稳定演示正文继续处理。
- ExternalLlmProvider 仅保留合规扩展点；默认不访问真实模型。
- 本地 Collector 使用 fixture，不执行真实网页抓取。
