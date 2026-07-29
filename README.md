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

真机上的简达助手 POST 请求同样通过 5174 的可信开发代理转发。代理会移除浏览器局域网 `Origin`，由后端继续执行已发布内容检索；这只作用于本地 Vite 开发代理，不放宽生产 CORS。页面会按实际处理方式显示“平台运行状态”“原文检索”“已审核内容 + AI 整理”或“通用 AI 参考”。若 External Provider 未启用或调用失败，有依据的问题仍可安全降级为原文检索。

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
5. 在左右对照页切换“原 PDF/原图”和“提取文本”。内置 PDF.js 阅读器支持翻页、缩放、适合宽度、全屏、重试和独立下载；原图支持缩放与下载。核对页码、引用片段和字段后完成审核。
6. 设置分类和来源；如确需公开上传原件，显式勾选“允许用户查看原文件”，再发布。
7. 打开用户 H5，查看刚发布的内容，切换 18/20/22/24px 字号，测试朗读、收藏、提取文本与获授权公开的原文件。

### 通用材料结构与原文件

- 上传时保存原文件名、MIME、字节大小和 SHA-256；磁盘读取时重新校验 SHA-256，不向前端暴露存储路径。
- 机构端原文件接口需要 JWT 和机构权限；公开端只有已发布且机构显式授权的材料可读取。
- PDF/原图接口支持 `Range`、`ETag` 和 `X-Content-SHA256`。在线阅读保持 `inline`，下载按钮使用 `download=true` 获取 `attachment` 和原始文件名，不依赖 iframe，避免 `X-Frame-Options` 或下载插件接管预览。
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

### 白名单网页文章演示

1. 使用 `org_admin / Jianda@123` 登录机构端，进入“上传材料”，切换到“导入网页文章”；`platform_admin` 也可以在“公开信息导入”使用同一组件。
2. 先查看预览中的来源、robots 状态、发布时间、正文摘要、封面类型和图片许可提示；预览不会创建材料。
3. 确认导入后会创建归属于当前登录机构的 `WEB_ARTICLE` 材料，并使用真实文档 ID 进入处理页。
4. 第三方原图必须在审核页确认图片来源；也可点击“更换为分类默认图”。未确认原图时发布会被后端拒绝。
5. 用户端首页和资讯详情使用固定比例、`object-fit: cover` 的响应式图片；图片失败自动回退本地分类图。
6. 原网页正文变化时可在导入记录中点击“重新采集”；已发布内容不会被该操作直接覆盖。

网页文章没有 PDF/图片原文件是正常状态。审核页直接展示已保存的网页正文快照、正文图片、
AI 摘要和可追溯字段，不会因原文件接口返回 404 而清空整页。非平台管理员只能查看和操作
当前机构的文章；跨机构访问返回 403，材料记录不存在返回 404。

网页采集只处理 `source_registry` 白名单中的公开页面，遵守 robots.txt、限速和站点访问
边界，不绕过登录、验证码、付费墙或防盗链。未明确允许图片下载的来源不下载原图，直接
使用不含金额、日期、人物或机构 Logo 的本地分类默认图。

网页资讯使用请求级 Prompt 版本 `web-v1.1`，保留旧摘要和通俗版结果兼容，同时增加三句话看懂、关系、行动清单、关键事实、常见误区、FAQ、适用范围和尚待确认。事实性模块的原文引用通过 segment 校验后才会保存；用户端可切换“快速看懂”和“完整解读”。

### Phase 9.3 来源运营、调度与预算

Phase 9.3 在白名单基础上增加来源运营配置和有界文章发现，支持 RSS 2.0、Atom、Sitemap/Sitemap index、JSON-LD Article/NewsArticle 以及人工配置的栏目页。新来源默认停用，调度开关默认关闭；只有平台管理员明确启用来源后，调度器才会在租约、单站限速、robots、SSRF、条数上限和失败重试边界内工作。

自动采集默认不调用真实 AI。`CRAWL_SCHEDULER_ENABLED` 与 `CRAWL_AUTO_AI_ENABLED` 默认均为 `false`；来源还需单独开启 `allow_auto_ai`，并同时满足每日文章预算与 Token 预算。预算不足时任务进入 `WAITING_BUDGET`，保存原因和预计恢复时间，不把它记作 AI 处理失败，也不会循环调用模型。自动 AI 关闭时新内容进入 `WAITING_APPROVAL`，必须由平台管理员人工批准。

采集产物始终先成为待审核材料，不会自动审核或自动发布。正文变化会创建新版本，线上已发布版本继续公开；新版本完成文字、来源和图片候选审核后才能替换。`allow_image_candidates` 控制是否下载少量候选数据并在机构端生成候选，`allow_image_cache` 只控制人工确认来源与使用依据后能否缓存公开；禁止缓存不会删除机构端候选。第三方图片候选记录 URL、来源页、发现方式、alt、尺寸、MIME、hash、缓存和权利状态；未填写来源与许可说明不能确认，未审核图片不会公开，拒绝或无合适候选时使用本地分类默认图。

平台管理员可在“权威来源”按来源执行三种受控操作：

- “发现文章”仅发现同源 URL，不创建材料、不调用 AI；
- “影子采集”抓取正文和图片策略预览，不创建材料、不调用 AI、不发布；
- “立即采集”创建材料并进入 `WAITING_APPROVAL`，仍需人工批准 AI、审核和发布。

图片发现通用支持 OpenGraph、JSON-LD、`src`、`data-src`、`data-original`、`data-lazy-src`、`data-echo`、`srcset`、`data-srcset`、`picture/source` 和 `video poster`，不依赖特定网站选择器。

当前 Collector 的边界：

- 不绕过登录、验证码、付费墙、防盗链和 robots 禁止规则；
- 不做无界站点爬取，只处理启用白名单和配置的发现入口；
- 动态脚本渲染、复杂反爬页面和需要登录的内容不在当前采集范围；
- 失败任务仅支持有限次数、人工可见的单条或批量重试；
- 真实来源上线前仍需完成影子运行、来源授权和人工发布口径验收。

默认测试不会调用真实模型。只有明确设置以下变量时才执行真实网页烟雾测试：

```powershell
$env:RUN_EXTERNAL_SMOKE = "1"
$env:EXTERNAL_SMOKE_URL = "https://白名单官方站点/尚未导入的文章"
npx playwright test tests/e2e/external-web-smoke.spec.ts
```

该测试会消耗真实模型额度并创建材料，但仍须经过机构端人工审核后才会发布。
旧版“导入—AI 处理—审核—发布—撤回”全链路 E2E 同样具有业务写入和模型调用副作用，
默认全量测试会跳过；只有获得明确授权并确认当前 Provider 后，才可设置
`RUN_MUTATING_E2E=1` 单独运行。普通 `npx playwright test` 不应设置这两个变量。

若本地代理或 VPN 使用 fake-IP DNS，将公网域名解析到 `198.18.0.0/15`，可仅在本地
评测时设置 `JIANDA_ALLOW_FAKE_IP_DNS=true`。该配置默认是 `false`，生产环境必须保持
关闭；它不会放行回环、其他私网或保留地址，也不能用于绕过网站访问限制。

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
- 网页 Collector 支持白名单内的人工 URL 预览以及有界 RSS、Atom、Sitemap、JSON-LD 和栏目发现；默认关闭自动调度和真实 AI，不执行无限制爬取，非白名单域名、robots 禁止页面和受限内容不会采集。
- 简达助手已接入可替换检索器和受预算保护的 External RAG，只召回 `PUBLISHED` 内容。状态问题由后端直接回答；有依据时可由 External Provider 严格基于最多三条已审核证据整理；低风险无依据问题可明确标注为“通用 AI 参考”；医疗诊断、政策资格、金额、办理材料等高风险问题无依据时拒绝猜测。
- 游客收藏、历史、偏好和助手会话尚未跨设备同步；办事提醒和用户账号将在后续版本开放。

## Phase 9.4 验收冲刺

- 材料处理已经改为后台异步任务：上传或导入后立即返回 `jobId`，处理页每 2 秒恢复并刷新
  阶段、进度、章节、Token 和耗时；完成后自动加载结果并提供原文对照审核入口。刷新页面或
  返回列表不会丢失任务状态。
- PDF 在线阅读与 AI 提取相互独立。阅读器优先使用 Range/ETag 加载，失败后自动尝试完整
  Blob，并区分登录失效、无权限、文件缺失、Range 异常、非 PDF 响应、加密或损坏等原因。
- PDF 和网页先识别材料类型，再按办事指南、活动通知、政策文件、标准规范、健康科普、
  反诈提醒、养老服务、新闻资讯或其他公共服务材料生成相应结构；长文按章节有界处理并保留
  页码、segment 和引用，不要求所有材料都生成同一套扁平字段。
- “权威来源管理”内含“来源列表 / 扫描与导入 / AI 等待队列 / 采集任务 /
  高级自动采集设置”。平台管理员可粘贴未预注册的官方网页，安全预览后确认来源身份；
  微信公众号按账号名称、主体、biz 和身份指纹区分，不能仅凭共享域名认定官方。
- 手动扫描的使用路径：选择来源 → “扫描最近文章” → 设置 1/3/7/30 天和关键词 →
  勾选未重复文章 → “批量保存所选并加入 AI 队列”。发现只返回 URL，影子采集只生成预览，
  立即采集只创建材料；三者都不会自动发布。
- “高级自动采集设置”保留来源级调度、文章和 Token 预算。全局能力开关与来源开关同时
  生效；来源默认不自动 AI，任何采集结果都必须人工审核和发布。
- 同一标签提供“历史封面补齐”：先按来源、类型、状态和日期预览，再确认执行。历史网页
  重新发现候选；获明确缓存许可的图片下载到本地公开接口，未获许可继续使用分类默认图；
  已上传 PDF 第一页和图片原件可作为可追溯本地封面。
- H5 内置 30 套中性分类素材（健康 6、养老政策 5、防诈 5、社区服务 5、文化学习 4、
  办事通知 5），按标题或文档 ID 稳定选择；真实封面加载失败时切换同类备用图，刷新不随机。
- 新增 `WEB_IMAGE_CANDIDATES_ENABLED` 与 `HISTORICAL_COVER_BACKFILL_ENABLED`。
  运行 `.\scripts\sync-env.ps1` 只会向本机 `.env` 添加缺失键，不覆盖已有值，也不会显示
  密钥、Token 或密码。
- 简达助手默认继续使用稳定的已发布内容检索；只有显式设置
  `ASSISTANT_EXTERNAL_ENABLED=true` 时才会把最多三条已审核证据发送给 External
  Provider。外部调用受每日次数和 Token 预算限制，失败自动降级为 retrieval。
- 助手响应区分 `status`、`retrieval`、`ai`、`general_ai` 四种模式；检索支持中文办事、
  材料、时间、地点、联系方式、费用、老年和反诈等同义词扩展，并通过接口保留替换全文
  检索实现的边界。
- External 回答必须引用已发布证据，不能补写证据之外的电话、日期、费用、地址、
  材料或适用条件；页面会区分“AI 基于已审核来源整理”和检索降级模式。
- iPhone Safari 朗读采用短段队列，暂停时取消当前队列，继续时从当前段创建新的
  utterance，不依赖 iOS 上不稳定的原生 pause/resume；离开页面会停止旧队列。
- 平台管理员工作台的“试运营数据”来自数据库聚合，包含来源、采集、审核、发布、
  AI、浏览、收藏、助手引用和人工修改率；机构管理员无权读取平台级指标。
- 平台管理员可通过独立 `/operations` 页面查看来源启停与最近采集、今日发现/采集/
  重复/失败、AI 队列与 Token 预算、待审图片和内容、已发布数、平均采集/AI 耗时以及
  最近错误和失败来源，所有数字均来自数据库聚合。
- External 响应恢复会归一化枚举别名、补齐安全的可选字段、隔离未知字段，将不确定事实
  放入 `uncertain_fields`，并记录不含正文或模型响应的 `repaired_paths`；无法可靠恢复
  的内容继续按 Schema 错误处理，不猜测材料事实。
- 自动调度、自动 AI、自动审核和自动发布仍默认关闭。第三方图片没有人工确认来源
  和许可说明时不得公开，无法确认时使用本地中文分类默认图。

微信公众号的直接公开文章 URL 可以导入并由管理员确认账号身份；只有来源存在稳定、公开、
合规的文章列表、官网同步栏目或人工配置入口时才允许自动扫描。系统不会使用非官方第三方
接口伪装“自动发现全部公众号文章”。

日常开发可运行 `.\scripts\verify-fast.ps1`；完整里程碑验证运行
`.\scripts\verify-full.ps1`。明日演示顺序和 iPhone 清单位于
`docs/ACCEPTANCE_TOMORROW.md`。
- 浏览器 TTS 的声音、断句和可用性取决于设备系统；“最近收听”仅保存在当前浏览器，不作为身份或审核凭证。
