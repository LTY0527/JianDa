# 简达

基于人工智能的公共服务信息适老化生成与阅读平台。课程版本提供机构端 Web、用户移动 H5、Spring Boot 后端和 FastAPI AI 服务，并保证在无 Docker、无真实模型 Key 时使用 H2 + MockProvider 完整演示。

## AI Provider

AI 服务默认使用确定性的 `MockProvider`，无需 API Key。设置 `LLM_PROVIDER=external` 后启用 OpenAI-compatible 的 DeepSeek `ExternalLlmProvider`。它先从带页码和段落 ID 的原文中提取可追溯事实，再仅使用已验证事实生成适老化摘要、通俗版、步骤卡片、风险提示、术语解释和朗读稿；任一阶段失败都会明确报错，不会静默回退到 Mock 数据。

External Provider 默认模型为 `deepseek-v4-flash`，也可通过 `EXTERNAL_LLM_MODEL=deepseek-v4-pro` 切换。不要使用已弃用的 `deepseek-chat` 或 `deepseek-reasoner`。提示词版本由 `JIANDA_PROMPT_VERSION` 控制：`v1` 保留兼容，默认 `v1.1` 增加分时受理、分人群材料、费用支付、领取邮寄、相对期限和更正信息等通用公共服务结构。代码不会记录完整原文、Authorization 请求头或模型推理内容。

开发者如需真实联调，可在本机未提交的 `.env` 中填写：

```env
LLM_PROVIDER=external
EXTERNAL_LLM_BASE_URL=https://api.deepseek.com
EXTERNAL_LLM_API_KEY=你的本机密钥
EXTERNAL_LLM_MODEL=deepseek-v4-flash
EXTERNAL_LLM_TIMEOUT_SECONDS=60
EXTERNAL_LLM_MAX_RETRIES=2
EXTERNAL_LLM_MAX_TOKENS=6000
EXTERNAL_LLM_THINKING=disabled
JIANDA_PROMPT_VERSION=v1.1
```

`.env` 不得提交到 Git。涉及公共服务材料时，还应先确认模型账号、数据出境、隐私和业务审核要求。

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

## 协作开发

所有功能、界面、测试和文档变更均采用小步中文进度提交。开始开发、提交前检查、提交信息格式和推送边界请遵守 [Git 协作与中文进度提交规范](docs/GIT_CONVENTION.md)。
## 环境要求

- Node.js 20+
- npm 10+
- JDK 17
- Maven 3.9+
- Python 3.11+；当前已在 Python 3.13 上完成验证，不支持 Python 3.9
- Docker 可选

本地开发默认使用 H2 + MockProvider，不需要 Docker，也不需要真实模型 Key。Python 3.13 是当前 Windows 验证环境，不是唯一支持版本；开发者可以使用任意 Python 3.11+ 解释器。

## 首次安装

### Windows PowerShell

先检查电脑上可用的 Python 解释器：

```powershell
py -0p
python --version
```

不要使用 Python 3.9 创建 AI 服务虚拟环境。必须确认选中的解释器版本不低于 3.11；电脑同时存在多个 Python 时，应显式指定版本。下面以当前已验证的 Python 3.13 为例：

```powershell
npm install
cd services\ai-service
py -3.13 -m venv .venv
& ".\.venv\Scripts\python.exe" -m pip install -r requirements.txt
cd ..\..
```

如果安装的是 Python 3.11，可将创建命令改为：

```powershell
py -3.11 -m venv .venv
```

### Bash

确认 `python3 --version` 不低于 3.11，再执行：

```bash
npm install
cd services/ai-service
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
cd ../..
```

## 重建 AI 虚拟环境

虚拟环境版本不正确或依赖损坏时，可在 PowerShell 中完整重建：

```powershell
cd services\ai-service
deactivate
Remove-Item -LiteralPath ".\.venv" -Recurse -Force
py -3.13 -m venv ".\.venv"
& ".\.venv\Scripts\python.exe" --version
& ".\.venv\Scripts\python.exe" -m pip install --upgrade pip
& ".\.venv\Scripts\python.exe" -m pip install -r requirements.txt
& ".\.venv\Scripts\python.exe" -m pip check
& ".\.venv\Scripts\python.exe" -m pytest -q
```

如果当前没有激活虚拟环境，`deactivate` 不可用时可跳过该行。删除 `.venv` 只会删除可重建的隔离环境，不会删除业务代码。Python 3.13 只是当前验证示例，实际可以显式选择任何 Python 3.11+ 解释器。

## 启动

PowerShell 可运行 `./scripts/dev.ps1`，Bash 可运行 `./scripts/dev.sh`。PowerShell 脚本会校验目录与 AI 虚拟环境版本，启动服务后逐项健康检查；只有检查成功的服务才会标记为 `Ready`，并记录实际监听 PID。按 Ctrl+C 时只清理本轮启动的服务；启动前已经健康的服务会显示为 `Ready (existing)`，不会被重复启动或自动停止。也可以打开四个终端分别运行：

```powershell
cd services\ai-service; & ".\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8001
cd services\backend; mvn spring-boot:run
npm run dev:institution
npm run dev:h5
```

服务地址：

- 机构端：`http://127.0.0.1:5173`
- 用户端：`http://127.0.0.1:5174`
- Spring Boot Swagger：`http://127.0.0.1:8080/swagger-ui/index.html`
- Spring Boot OpenAPI：`http://127.0.0.1:8080/v3/api-docs`
- FastAPI 文档：`http://127.0.0.1:8001/docs`
- FastAPI 健康检查：`http://127.0.0.1:8001/health`

## 手机真机测试

手机和电脑需连接同一局域网。先用 `ipconfig` 或下面的 PowerShell 命令确认电脑的局域网 IPv4 地址，不要把具体 `192.168.*` 地址提交到仓库：

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
  Select-Object InterfaceAlias, IPAddress
```

局域网开发使用 H5 同源代理：浏览器只访问 5174，`/api` 由 Vite 转发到本机后端。先启动后端，再运行专用脚本：

```powershell
cd services\backend
mvn spring-boot:run

# 在另一个 PowerShell 中，从项目根目录执行
./scripts/dev-h5-lan.ps1
```

`dev-h5-lan.ps1` 会清除当前进程中的 `VITE_API_BASE_URL`，设置 `VITE_PROXY_TARGET=http://127.0.0.1:8080`，并输出本机和代理测试地址。随后在手机浏览器验证：

- 用户端：`http://电脑局域网IP:5174`
- 同源代理：`http://电脑局域网IP:5174/api/public/items`
- 后端直连对照：`http://电脑局域网IP:8080/api/public/items`

真机代理测试时不要设置 `VITE_API_BASE_URL`，否则浏览器会绕过 5174 同源代理。`VITE_API_BASE_URL` 仅保留给需要显式 API 地址的部署环境覆盖；代理目标由 `VITE_PROXY_TARGET` 配置，默认是 `http://127.0.0.1:8080`。仓库配置不包含固定局域网 IP，也不会删除或重写 `/api` 前缀。

可用下面三个地址检查响应头和正文：

```powershell
curl.exe -i http://127.0.0.1:8080/api/public/items
curl.exe -i http://127.0.0.1:5174/api/public/items
curl.exe -i http://电脑局域网IP:5174/api/public/items
```

正常结果均为 `200`、非空 JSON，`Content-Type` 为 `application/json;charset=UTF-8`。若 5174 返回 HTML，说明 Vite 代理未加载；502 表示代理无法连接后端；404 表示请求路径错误；200 空正文需继续检查代理响应和过滤器。修改 Vite 配置或环境变量后必须彻底停止并重启 H5。

普通 HTTP 局域网下部分浏览器不提供 `crypto.randomUUID`，用户端会自动改用 Web Crypto 随机字节并提供最终兼容兜底，不会在 Vue 挂载前白屏。已保存的游客 ID 会直接复用，它只关联当前浏览器中的匿名收藏、历史和偏好，不是身份认证凭证。原有 `npm run dev:h5` 和 localhost 开发方式保持不变。

真机上的简达助手 POST 请求同样通过 5174 的可信开发代理转发。代理会移除浏览器局域网 `Origin`，由后端继续执行已发布内容检索；这只作用于本地 Vite 开发代理，不放宽生产 CORS。发送成功后页面会显示“当前使用已审核内容检索回答”和引用依据。若 AI 服务未启动，当前检索降级仍可工作。

“听一听”使用浏览器 Web Speech API (`speechSynthesis`) 朗读已发布内容，不上传或生成音频。不同手机的中文声音由系统浏览器决定；iPhone Safari 通常要求用户先点击播放。页面支持播放、暂停、继续、停止、上一条/下一条和 0.8/1.0/1.2 倍速。不支持该 API 时会显示文字降级提示。

如果终端关闭后仍有子进程占用开发端口，可运行：

```powershell
./scripts/stop.ps1
```

停止脚本会显示 5173、5174、8080、8001 的实际监听 PID、进程名和命令行；只有命令行确认属于当前 JianDa 工作区时才会按进程树停止，并在退出前再次确认端口状态。

## 常见故障排查

### Click 的 `match` 语句出现 `SyntaxError`

如果错误位于 `click/utils.py` 的 `match` 语句，通常是因为 `.venv` 由 Python 3.9 创建。请使用 Python 3.11+ 按上面的流程重建 `.venv`。不要手动修改 `site-packages`，也不要通过长期降级第三方依赖来兼容 Python 3.9。

### 访问 8001 出现 `ERR_CONNECTION_REFUSED`

进入 `services\ai-service` 后依次检查端口、模块导入和前台启动输出：

```powershell
Test-NetConnection 127.0.0.1 -Port 8001
& ".\.venv\Scripts\python.exe" -c "from app.main import app; print('AI import OK')"
& ".\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

### pip 提示 `Cannot connect to proxy`

如果当前终端继承了无效代理，可临时清除代理环境变量后重试安装：

```powershell
Remove-Item Env:HTTP_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:HTTPS_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:ALL_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:PIP_PROXY -ErrorAction SilentlyContinue
```

这只影响当前 PowerShell 会话。

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
5. 在左右对照页切换“原 PDF/原图”和“提取文本”，核对页码、原文片段及结构化字段，修改并确认字段后完成审核。
6. 设置分类和来源；如确需公开上传原件，显式勾选“允许用户查看原文件”，再发布。
7. 打开用户 H5，查看刚发布的内容，切换 18/20/22/24px 字号，测试朗读、收藏、提取文本与获授权公开的原文件。

### 通用材料结构与原文件

- 上传时保存原文件名、MIME、字节大小和 SHA-256；磁盘读取时重新校验 SHA-256，不向前端暴露存储路径。
- 机构端原文件接口需要 JWT 和机构权限；公开端只有已发布且机构显式授权的材料可读取。
- PDF/原图接口支持 `Range`、`ETag` 和 `X-Content-SHA256`，便于浏览器按需预览。
- v1.1 同时保留旧的扁平字段，并生成 `AUDIENCE_RULES`、`SERVICE_SCHEDULE`、`CONDITIONAL_MATERIALS`、`FEES`、`RESULT_DELIVERY`、`DEADLINE_RULES`、`AMENDMENTS`。
- 每个结构化条目都带原文引用、页码、段落 ID 和人工复核标记；相对期限不会伪造为固定日期，可选材料不会变成必需材料。
- External 结果缓存键包含文件 SHA-256、模型、提示词版本和 Schema 版本。缓存仅用于完全相同的文件与配置，失败结果不缓存；当前为 AI 进程内缓存，容器重启后失效。

### 用户端消费级 App 演示

用户端固定五项一级导航：`/` 首页、`/listen` 听一听、`/assistant` 简达助手、`/services` 办事、`/profile` 我的；一级页面不显示返回按钮。`/news` 调整为从首页进入的二级资讯页，详情、收藏、历史、设置和助手历史等页面继续使用带 fallback 的安全返回。

1. 在首页查看重要提醒、今日必看和精简办事快报，点击“查看全部权威资讯”进入资讯频道。
2. 打开“听一听”，选择今日早报、政策、健康、反诈、养老、收藏或最近收听，测试连续播放和倍速。
3. 在办事行动中心按服务对象、事项类型和发布机构筛选，直接核对适用对象、期限、地点、材料数和步骤数。
4. 收藏一条内容后到“我的 → 我的收藏”查看；打开详情和播放内容会分别写入浏览与收听历史。
5. 在阅读设置切换 18/20/22/24px 字号、语速、高对比度、自动朗读和关注频道。
6. 从底部中央入口进入“简达助手”，发送问题并核对检索模式、来源机构、原文片段及详情链接。

收藏快照、浏览历史、收听历史、阅读偏好和助手会话保存在当前浏览器 `localStorage`，游客不需要登录。收藏和收听队列会与公开列表求交集，因此已撤回内容不会继续显示。清除浏览器站点数据或在页面内执行清除操作会删除相应本机记录。

### 权威公开信息演示

1. 使用 `platform_admin / Jianda@123` 登录机构端。
2. 进入“公开信息导入”，选择反诈、健康或社区养老 fixture，也可以从白名单来源手工录入。
3. 点击“发起 AI”，在原文对照页核对真实标题、原文、页码与引用片段。
4. 完成字段审核，在发布页确认动态带入的分类、来源名称、来源 URL 和摘要。
5. 发布后打开返回的用户 H5 链接，查看风险提示、术语解释、语音、大字模式和原文。
6. 回到“已发布内容”撤回，原 H5 链接将立即变为不可访问。

## 测试

```powershell
npm run typecheck
npm run build
npm run test:e2e
cd services\backend; mvn test; mvn package
cd ..\ai-service; & ".\.venv\Scripts\python.exe" -m pytest -q
```

Phase 7.3 已完成 375×812、768×1024、1440×900 三档视口和核心业务闭环验收。验收清单、缺陷台账、最终报告及 19 张真实运行截图分别位于：

- `docs/PHASE7_3_ACCEPTANCE_CHECKLIST.md`
- `docs/PHASE7_3_ISSUES.md`
- `docs/PHASE7_3_ACCEPTANCE_REPORT.md`
- `artifacts/phase7-3/`

Phase 7.4 自动化验收已覆盖助手同源 POST、检索引用、错误重试、Web Speech 模拟、五项导航和 375/768/1440 响应式布局。代码层 P0/P1 未发现未解决项；真实 iPhone Safari 的助手发送与系统语音播放、Android Chrome、目标老年用户可用性和业务发布口径仍需人工确认。在完成真机确认前，结论为“代码层建议进入 Phase 8，真机人工确认待完成”，本轮未启动 Phase 8。

## 数据与安全

开发默认数据保存在 `services/backend/data` 的 H2 文件和根目录 `uploads/`。正式配置使用 MySQL 8 与同一套 Flyway 表结构。密码使用 BCrypt；JWT 密钥来自环境变量；上传限制为 20MB 且只接受 PDF/PNG/JPG，文件名会清理并使用随机存储名防止路径穿越。

## Docker（可选）

安装 Docker 后运行 `docker compose up --build -d`，会启动 MySQL 8、AI 服务、Spring Boot 后端以及托管两个 Vue 应用的 Nginx 前端。默认访问地址为用户端 `http://127.0.0.1`、机构端 `http://127.0.0.1:8090`。AI 容器使用 Python 3.11，符合项目最低版本要求，不需要与本机验证版本完全一致。

公开信息 fixture 默认从后端 JAR 内的 `classpath:fixtures/public-information.json` 读取，因此 IDE、`java -jar` 和 Docker 使用同一份内置资源。如需显式使用外部 fixture，可设置 `JIANDA_PUBLIC_FIXTURE`；一旦配置，路径必须在后端运行环境中存在，否则后端会报告包含目标路径的明确错误。容器环境不要填写 Windows 宿主机路径。

## 当前限制

- OCR 未安装时，图片材料使用手工正文或稳定演示正文继续处理。
- ExternalLlmProvider 已具备真实模型适配与自动回归；默认仍使用 MockProvider，不会主动访问真实模型。生产启用前需完成数据合规和业务验收。
- 本地 Collector 使用 fixture，不执行真实网页抓取。
- 简达助手当前采用稳定关键词/分类检索，只从 `PUBLISHED` 内容生成带引用的理解提示，不是开放域聊天或医疗、金融、政策决策工具；没有可靠依据时会明确拒绝补充事实，后续可在现有接口边界内替换为 RAG。
- 游客收藏、历史、偏好和助手会话尚未跨设备同步；办事提醒和用户账号将在后续版本开放。
- 浏览器 TTS 的声音、断句和可用性取决于设备系统；“最近收听”仅保存在当前浏览器，不作为身份或审核凭证。
