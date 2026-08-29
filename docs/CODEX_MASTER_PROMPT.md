# Codex 新工作区开发总提示词：简达

你是一名资深产品工程师、Java 后端工程师、Vue 前端工程师和 Python AI 工程师。请在当前全新的空白工作区中，从零实现一个可运行、可演示、结构清晰的课程项目：

## 项目名称

**简达——基于人工智能的公共服务信息适老化生成与阅读平台**

## 一、工作方式

1. 先检查当前工作区、系统环境和已安装工具。
2. 先创建并维护：
   - `docs/PROJECT_BRIEF.md`
   - `docs/ARCHITECTURE.md`
   - `docs/API.md`
   - `docs/TASKS.md`
   - `docs/UI_GUIDE.md`
3. 将任务拆成可运行阶段。每完成一个阶段：
   - 更新 `docs/TASKS.md`
   - 运行构建或测试
   - 修复错误
   - 保证仓库仍可启动
4. 除非遇到无法判断的外部依赖问题，不要频繁询问。
5. 不要伪造完成或测试结果。
6. 初始化 Git，保持小而清晰的提交；若不能提交，至少维护变更记录。
7. 不删除已有文件。
8. 关键代码写必要中文注释，但不要逐行注释。
9. 页面、接口、示例数据和提示全部使用自然中文。
10. 最终提供完整 README、启动命令、演示账号、演示流程、已实现/未实现功能和测试结果。

## 二、产品逻辑

系统有两种内容来源：

1. 公共服务机构上传 PDF、图片、扫描件和通知；
2. 平台运营人员导入政府、医院、主流媒体等权威公开信息。

统一流程：

**获取内容 → 提取正文 → AI 结构化与通俗化 → 生成步骤卡片 → 关键字段原文追溯 → 人工审核 → 分类发布 → 用户大字或语音阅读。**

中老年用户是主要使用者；社区、医院、政务服务中心等机构是主要客户和付费方。

## 三、本次实现范围

### 3.1 机构端 Web

页面：

1. 登录页
2. 工作台
3. 材料管理列表
4. 上传材料页
5. AI 处理进度与结果页
6. 原文与 AI 结果左右对照审核页
7. 审核与发布页
8. 已发布内容管理页
9. 权威公开信息导入页
10. 操作日志页

功能：

- 账号密码登录
- JWT 鉴权
- 按机构隔离数据
- 上传 PDF/PNG/JPG
- 保存原始文件
- 创建和查看处理任务
- 展示结构化字段：适用对象、条件、材料、时间、地点、费用、步骤、注意事项、联系方式
- 通俗版和步骤卡片
- 字段对应页码、段落和原文片段
- 人工修改
- 关键字段确认
- 用户端预览
- 审核发布
- 撤回
- 审核记录和操作日志

### 3.2 平台运营能力

不单独建第三个前端，放在机构端 Web 中，用角色区分。

平台管理员可以：

- 查看所有机构
- 管理内容来源
- 手工导入权威公开信息：标题、来源、URL、发布时间、正文、分类
- 创建 AI 任务
- 审核发布平台资讯
- 撤回和更正内容

创建 `ContentCollector` 接口和本地演示实现，预留 RSS、API 和网页白名单采集。本次不要依赖真实网络爬虫，使用本地 fixtures 或手工导入保证离线可运行。

### 3.3 用户端移动 H5

页面：

1. 首页
2. 分类列表
3. 搜索页
4. 办事指南详情
5. 权威资讯详情
6. 步骤详情
7. 收藏页
8. 设置页
9. 原文查看页

首页：

- 搜索框
- 时政、健康、养老、反诈、生活服务、文化
- 办事指南专区
- 权威来源标识
- 发布日期
- 内容卡片

详情页：

- 三句话看懂
- 我是否符合条件
- 需要准备什么
- 什么时候办理
- 到哪里办理
- 办理步骤
- 注意事项
- 专业术语解释
- 来源、发布时间、原文
- 大字模式
- 语音播放
- 收藏

语音第一版使用浏览器 `speechSynthesis`，支持播放、暂停、停止和语速。

## 四、技术栈

### 4.1 Monorepo

```text
jianda/
  apps/
    institution-web/
    user-h5/
  services/
    backend/
    ai-service/
  packages/
    shared-types/
    shared-ui/
  docs/
  scripts/
  fixtures/
  docker-compose.yml
  README.md
```

### 4.2 机构端

- Vue 3
- TypeScript
- Vite
- Vue Router
- Pinia
- Element Plus
- Axios
- Lucide 统一线性图标
- ESLint
- Prettier

### 4.3 用户端

- Vue 3
- TypeScript
- Vite
- Vue Router
- Pinia
- 自定义移动端组件
- 不把机构端组件库直接搬到移动端
- 可共享类型、请求封装和设计 token

### 4.4 Java 后端

- Java 17
- Spring Boot 3.x
- Maven
- Spring Security
- JWT
- MyBatis-Plus
- MySQL 8
- Flyway
- Bean Validation
- OpenAPI/Swagger
- 文件上传
- 统一响应
- 全局异常处理
- RBAC
- 操作日志
- CORS
- 可选 H2 测试配置，MySQL 为正式配置

### 4.5 AI 服务

- Python 3.11+
- FastAPI
- Pydantic
- PyMuPDF 或等价 PDF 工具
- OCR 适配器：有 Tesseract 时使用，无 OCR 时允许手工粘贴
- LLM Provider：
  - `MockProvider`
  - `ExternalLlmProvider`
- 默认 Mock，保证无 Key 可运行
- 所有真实模型配置写入 `.env.example`
- 不硬编码密钥

## 五、核心数据模型

至少实现：

### organization

id, name, code, type, status, created_at, updated_at

### staff_user

id, organization_id, username, password_hash, display_name, role, status, created_at, updated_at

角色：PLATFORM_ADMIN, ORG_ADMIN, REVIEWER

### content_source

id, organization_id(nullable), source_type(UPLOAD/PUBLIC_IMPORT), source_name, source_url, publisher, published_at, imported_at, status

### source_document

id, organization_id, content_source_id, title, file_name, file_type, storage_path, raw_text, page_count, processing_status, created_by, created_at, updated_at

状态：UPLOADED, EXTRACTING, PROCESSING, WAITING_REVIEW, REVIEWED, PUBLISHED, FAILED, WITHDRAWN

### document_segment

id, document_id, page_no, segment_no, text, start_offset, end_offset

### processing_job

id, document_id, job_type, status, progress, error_message, started_at, finished_at

### extracted_field

id, document_id, field_type, field_value, page_no, segment_id, source_quote, confidence, review_status, reviewer_id, reviewed_at

字段类型：TARGET_AUDIENCE, ELIGIBILITY, MATERIAL, START_DATE, END_DATE, LOCATION, FEE, CONTACT, WARNING, EXCEPTION

### generated_content

id, document_id, content_type, title, content_json, plain_text, version, status, created_at, updated_at

类型：SUMMARY, PLAIN_LANGUAGE, STEP_CARDS, TERM_EXPLANATION, AUDIO_SCRIPT

### review_record

id, document_id, reviewer_id, action, comment, before_snapshot, after_snapshot, created_at

### published_item

id, document_id, slug, title, summary, category, cover_type, published_by, published_at, status, source_name, source_url

### user_preference

id, anonymous_user_id/user_id, font_size, speech_rate, high_contrast, updated_at

### favorite

id, anonymous_user_id/user_id, published_item_id, created_at

### operation_log

id, operator_id, organization_id, action, target_type, target_id, result, ip, created_at

## 六、核心 API

统一 `/api` 前缀并生成 OpenAPI。

认证：

- POST `/api/auth/login`
- GET `/api/auth/me`

材料：

- POST `/api/documents`
- POST `/api/documents/{id}/upload`
- GET `/api/documents`
- GET `/api/documents/{id}`
- POST `/api/documents/{id}/process`
- GET `/api/documents/{id}/jobs`
- GET `/api/documents/{id}/segments`
- GET `/api/documents/{id}/fields`
- GET `/api/documents/{id}/generated`

审核：

- PUT `/api/documents/{id}/fields/{fieldId}`
- PUT `/api/documents/{id}/generated/{contentId}`
- POST `/api/documents/{id}/review`
- GET `/api/documents/{id}/reviews`
- POST `/api/documents/{id}/publish`
- POST `/api/documents/{id}/withdraw`

公开信息：

- POST `/api/public-sources/import`
- GET `/api/public-sources`
- POST `/api/public-sources/{id}/process`

用户公开接口：

- GET `/api/public/items`
- GET `/api/public/items/{slug}`
- GET `/api/public/categories`
- GET `/api/public/search`
- POST `/api/public/items/{id}/favorite`
- DELETE `/api/public/items/{id}/favorite`

AI 内部接口：

- POST `/internal/extract-text`
- POST `/internal/analyze`
- GET `/health`

## 七、Mock AI 要求

无真实模型 Key 也必须可演示。

老年补贴申请示例稳定返回：

- 适用对象
- 条件
- 材料
- 时间
- 地点
- 步骤
- 联系方式
- 页码和原文片段
- 通俗版
- 4–5 个步骤卡片
- 语音稿

公开资讯稳定返回：

- 三句话看懂
- 重点内容
- 专业术语解释
- 风险提示
- 来源信息

不要随机变化，便于测试和答辩。

## 八、商业化 UI 要求

### 总体风格

目标是真实可商用 SaaS 和公共服务产品，不是 AI 模板。

使用：

- 白色或轻米色背景
- 深蓝、墨绿或蓝绿色主色
- 深灰正文
- 少量橙色提醒
- 8px 间距
- 8–12px 圆角
- 轻边框
- 克制阴影
- 统一图标
- 真实中文数据

禁止：

- 大面积紫色渐变
- 玻璃拟态
- 霓虹发光
- 巨大圆角卡片堆叠
- 随机机器人、星光、魔法棒
- 无意义插画
- 超大标题和过度留白
- 英文占位符
- 虚假 AI 评分
- 通用 AI Dashboard 模板感

### 机构端

- 左侧导航
- 顶部机构信息和账号
- 合理主内容宽度
- 真实表格、筛选、分页
- 状态：待处理、处理中、待审核、已发布、失败、已撤回
- 左原文右结果
- 风险字段用克制提醒色
- 空、加载、错误、权限状态齐全

### 用户端

- 移动优先，基准宽度 375px
- 默认字体至少 18px
- 行高约 1.8
- 点击区至少 48px
- 支持 18/20/22/24px
- 底部导航 4–5 项
- 主要操作：听、大字、步骤、原文
- 重要日期、材料和地点独立展示
- 不只依赖颜色
- 适配 375、768、1440

创建统一 CSS variables：
`--color-primary`, `--color-primary-dark`, `--color-success`, `--color-warning`, `--color-danger`, `--color-text`, `--color-muted`, `--color-border`, `--color-bg`, `--radius-sm`, `--radius-md`, `--shadow-sm`, `--space-1` 到 `--space-8`。

## 九、示例账号和数据

机构：

- 浦江街道社区服务中心
- 城市人民医院
- 简达平台运营中心

账号：

- `platform_admin / Jianda@123`
- `org_admin / Jianda@123`
- `reviewer / Jianda@123`

示例内容：

1. 老年补贴申请指南
2. 医院门诊就医流程
3. 反诈提醒
4. 高血压日常管理科普
5. 社区养老服务申请

README 明确这些只是演示账号。

## 十、工程要求

1. 前后端统一类型或 OpenAPI 生成类型。
2. 后端统一响应：code, message, data, timestamp。
3. 全局异常处理。
4. 密码 BCrypt。
5. JWT 密钥来自环境变量。
6. 上传限制类型和大小。
7. 清理文件名，防路径穿越。
8. 机构数据隔离。
9. 平台管理员可跨机构。
10. 审核、发布、撤回写日志。
11. 发布前必须有审核记录。
12. 原文不可被生成内容覆盖。
13. AI 服务不可用时：
    - 已发布内容仍可访问
    - 新任务失败或等待
    - 可人工录入和编辑
14. README 提供 PowerShell 和 Bash。
15. `.env.example`。
16. Docker Compose MySQL。
17. Flyway 初始化。
18. 至少有后端服务测试、认证测试、上传审核发布主流程测试、Mock AI 测试、前端组件或 E2E 冒烟测试。

## 十一、启动体验

根目录尽量提供：

- `scripts/dev.ps1`
- `scripts/dev.sh`

端口：

- 机构端 5173
- 用户端 5174
- Java 后端 8080
- AI 服务 8001
- MySQL 3306

README 写清环境、启动、账号、流程和常见错误。

## 十二、实现顺序

Phase 0：检查环境、创建文档、目录、Git 和任务清单。

Phase 1：两个前端、后端、AI 服务、Docker Compose 和 README 可启动。

Phase 2：数据库、账号、登录、RBAC、机构隔离。

Phase 3：材料列表、上传、文件保存、任务、Mock AI、字段、通俗版和步骤卡片。

Phase 4：原文对照、修改、审核、发布、撤回、日志。

Phase 5：用户首页、分类、搜索、办事详情、资讯详情、字号、语音、原文、收藏。

Phase 6：平台运营角色、公开信息导入、Collector 接口、本地 fixture，与材料共用流程。

Phase 7：商业 UI、响应式、状态、测试、文档和答辩数据。

## 十三、验收标准

1. 按 README 可启动。
2. 演示账号可登录。
3. 可上传示例 PDF 或图片。
4. 可查看处理状态。
5. 可查看 Mock AI 字段。
6. 可查看字段原文页码或片段。
7. 可修改和审核。
8. 可发布。
9. 用户端可看到发布内容。
10. 用户端可切换字号和播放语音。
11. 可查看来源和原文。
12. 平台管理员可导入公开信息。
13. 公开信息走同一流程后发布。
14. 375、768、1440 不明显错位。
15. 构建和测试通过。
16. 无硬编码密钥。
17. README、API 和任务文档完整。

## 十四、现在开始

请先执行 Phase 0：

1. 检查当前目录和环境。
2. 输出简短环境检查结果。
3. 创建项目文档和任务清单。
4. 给出目录结构。
5. 然后直接开始 Phase 1，不要只停留在方案描述。
