# API 说明

统一响应：`{ code, message, data, timestamp }`。公开接口无需 JWT，其余接口使用 `Authorization: Bearer <token>`。

## 认证

- `POST /api/auth/login`：账号密码登录。
- `GET /api/auth/me`：当前账号、角色与机构。

## 材料与处理

- `POST /api/documents/metadata-preview`：上传 PDF/PNG/JPG 后预识别标题、来源、文号和材料内权威证据；不创建正式文档或处理任务。
- `POST /api/documents`，`POST /api/documents/{id}/upload`
- `GET /api/documents`，`GET /api/documents/{id}`
- `POST /api/documents/{id}/process`
- `GET /api/documents/{id}/jobs|segments|fields|generated`
- `GET /api/documents/{id}/original-file`：需要 JWT 和文档所属机构权限；返回原始 PDF/PNG/JPG，支持 `Range`、`ETag` 和 `X-Content-SHA256`。默认 `inline` 供阅读器在线读取；传 `download=true` 时返回 `attachment` 和原始文件名。

预识别结果的 `authority_status` 仅表示材料内部证据：`DOCUMENT_EVIDENCE` 为存在明确发布机构证据，`UNCONFIRMED` 为无法确认，`CONFLICT` 为候选冲突；不表示官网或外部数据库已经核验。正式处理结果的 `generated` 可包含 `SESSIONS`，其中日期、时间、地点和原文追溯信息作为同一场次保存。

v1.1 通用结构还包括：

- `AUDIENCE_RULES`：适用人群与办理/健康条件；
- `SERVICE_SCHEDULE`：服务窗口和停办规则；
- `CONDITIONAL_MATERIALS`：按人群区分的必需与可选材料；
- `FEES`：费用类型、已知金额/收费规则和支付方式；
- `RESULT_DELIVERY`：窗口领取、邮寄及相关费用规则；
- `DEADLINE_RULES`：固定日期、相对期限、容量限制和分渠道截止；
- `AMENDMENTS`：原信息、更正信息和生效优先级。

以上结构条目包含 `source_quote`、`page_no`、`segment_id` 和 `needs_human_review`。`jobs` 同时返回处理阶段、Schema 版本、缓存命中状态、阶段耗时和 token 计数。

## 审核发布

- `PUT /api/documents/{id}/fields/{fieldId}`
- `PUT /api/documents/{id}/generated/{contentId}`
- `POST /api/documents/{id}/review|publish|withdraw`
- `GET /api/documents/{id}/reviews`

## 权威来源与导入

以下接口仅允许 `PLATFORM_ADMIN`：

- `GET|POST /api/public-sources`：列出或新增白名单来源。
- `PUT /api/public-sources/{id}/enabled`：启用或停用来源。
- `GET /api/public-sources/fixtures`：列出稳定离线 fixture。
- `POST /api/public-sources/import/fixture/{fixtureId}`：导入指定 fixture。
- `POST /api/public-sources/import/manual`：手工导入白名单域名下的公开正文。
- `GET /api/public-sources/imports`：查看导入记录、状态与失败原因。
- `GET /api/public-sources/imports/{documentId}`：预览原文及来源元数据。
- `POST /api/public-sources/imports/{documentId}/process`：进入现有 AI 处理流程。

导入会校验来源启用状态与 URL 主机名，并以来源 URL 和规范化正文 SHA-256 拦截重复内容。

### 白名单网页文章

- `GET /api/web-articles/sources`：平台管理员查看网页域名白名单、来源等级、图片缓存、自动采集预留配置和最近采集信息。
- `GET /api/web-articles/jobs`：平台管理员查看采集任务。
- `POST /api/web-articles/jobs/{jobId}/stop`：停止仍在等待或运行的任务。
- `POST /api/web-articles/{documentId}/recrawl`：正文 hash 未变化时返回 `contentChanged=false`、`cacheHit=true`，不清理结果且不调用 DeepSeek；已发布网页变化时保留线上版本，创建带 `previous_version_id` 的新材料，完成 AI 处理后进入待人工审核。
- `GET /api/public/items/{slug}/original-file`：只允许已公开原文件的 PDF/IMAGE；WEB_ARTICLE、物理文件缺失或未授权时返回 404。传 `download=true` 切换为下载。

- `GET /api/web-articles/sources`：仅 `PLATFORM_ADMIN`；查看网页来源注册表、权威级别、速率和图片缓存许可。
- `POST /api/web-articles/preview`：`PLATFORM_ADMIN`、`ORG_ADMIN`；校验白名单与 robots.txt，提取 canonical URL、标题、来源、发布时间、正文和封面候选；预览不会创建正式材料。
- `POST /api/web-articles/import`：`PLATFORM_ADMIN`、`ORG_ADMIN`；确认预览后创建归属于当前登录机构的 `WEB_ARTICLE` 材料、真实正文 segment 和待处理记录。
- `POST /api/web-articles/{documentId}/recrawl`：`PLATFORM_ADMIN`、`ORG_ADMIN`；手动重新采集尚未发布的网页材料。
- `POST /api/web-articles/{documentId}/cover/confirm`：`PLATFORM_ADMIN`、`ORG_ADMIN`、`REVIEWER`；人工确认当前第三方封面及来源。
- `POST /api/web-articles/{documentId}/cover/category-default`：`PLATFORM_ADMIN`、`ORG_ADMIN`、`REVIEWER`；更换为简达本地分类默认图。

### Phase 9.3 来源运营与采集任务

以下运营接口仅允许 `PLATFORM_ADMIN`：

- `GET /api/source-registries`、`GET /api/source-registries/{id}`：查看来源域名、发现方式、调度时间、每轮上限、安全默认值、文章/Token 预算和最近运行状态。
- `POST /api/source-registries`、`PUT /api/source-registries/{id}`：新增或更新来源。新来源固定为停用、禁止原图缓存、禁止自动采集、要求人工审核；启用由独立接口完成。
- `PUT /api/source-registries/{id}/enabled`：显式启用或停用来源。
- `POST /api/source-registries/{id}/discover`：对单个启用来源执行一次有界发现；只返回候选 URL，不创建材料、不调用 AI。
- `POST /api/source-registries/{id}/shadow`：抓取指定候选并返回正文、封面策略和图片候选预览；不创建材料、不调用 AI、不发布。
- `POST /api/source-registries/{id}/collect`：确认指定候选后创建材料并进入 `WAITING_APPROVAL`；不会自动审核或发布。
- `GET /api/crawl-tasks`、`GET /api/crawl-tasks/{id}`：按状态/来源查看任务计数、阶段、错误摘要和逐条错误队列。
- `POST /api/crawl-tasks`：创建采集任务。
- `POST /api/crawl-tasks/{id}/cancel`：取消仍可取消的任务。
- `POST /api/crawl-tasks/errors/{errorId}/retry`：只重试指定、未解决且可重试的错误。
- `POST /api/crawl-tasks/{id}/retry-failures`：确认后批量重试该任务仍可重试的失败项。

### Phase 9.3 图片候选与内容版本

- `GET /api/web-articles/{documentId}/image-candidates`：查看候选 URL、来源页、来源名、发现方式、alt、尺寸、MIME、hash、缓存、权利和审核状态。
- `POST /api/web-articles/image-candidates/{candidateId}/approve`：请求体为 `sourceName`、`usageBasis`；二者均不能为空。
- `POST /api/web-articles/image-candidates/{candidateId}/reject`：记录拒绝原因并安全回退分类默认图。
- `POST /api/web-articles/{documentId}/recrawl`：正文不变时返回缓存结果；已发布正文变化时创建带 `version_root_id`、`previous_version_id`、`version_no` 和 hash 变化摘要的新待审核版本，旧版本继续公开。

### Phase 9.3 AI 队列、预算与连续阅读

- `GET /api/ai-queue?status=`：查看 `WAITING_APPROVAL`、`WAITING_BUDGET` 等队列状态、原因、预计 Token 和恢复时间。
- `POST /api/ai-queue/{queueId}/approve`：平台管理员人工批准待处理内容。
- `POST /api/ai-queue/{queueId}/execute`：执行已批准且预算预留成功的任务；产物仍进入人工审核，不自动发布。
- `GET /api/public/items/{slug}/neighbors`：返回 `previous` 与 `next`，按置顶、重要度、发布时间和 ID 稳定排序；优先同分类，再回退全局已发布内容，首尾返回 `null`。

预算等待不会调用 AI，实际 Token 为 0，并返回自然语言原因和预计恢复时间。API 不返回 API Key、Authorization、Cookie、内部堆栈、完整提示词或模型响应。

请求体为 `{ "url": "https://白名单域名/官方文章" }`。来源注册表的
`allow_image_candidates=true` 允许 AI 服务下载少量候选数据并校验 HTTP 状态、媒体
类型、尺寸、比例和哈希，以便生成仅机构端可见的候选；`allow_image_cache` 只控制候选
经人工填写来源和使用依据后能否缓存公开，设置为 `false` 不会删除候选。第三方封面未经
人工确认不能发布，用户端安全回退分类默认图。`查看官方原文` 始终使用文章 canonical
URL，不使用图片 URL。

除平台管理员外，上述文档操作均校验 `organization_id`。跨机构访问返回 403，记录不存在
返回 404。`WEB_ARTICLE` 的 `storage_path` 可以为空；此时
`GET /api/documents/{id}/original-file` 返回带说明的 404，但文档详情、字段、生成内容、
网页正文快照、审核和发布接口仍正常可用。

## 用户端公开接口

- `GET /api/public/items|categories|search`
- `GET /api/public/items/{slug}`
- `GET /api/public/items/{slug}/original-file`：仅已发布且发布时显式设置 `allowPublicOriginal=true` 的材料可用；同样支持字节范围读取和内容 SHA-256 校验。
- `POST|DELETE /api/public/items/{id}/favorite`
- `GET /api/public/assistant/suggestions`：根据当前已发布分类返回稳定推荐问题。
- `POST /api/public/assistant/chat`：状态问题由后端直接回答；公共服务问题通过可替换检索器仅召回 `PUBLISHED` 内容，并返回回答、行动建议、来源引用、安全提示和 `mode`。`mode` 为 `status`、`retrieval`、`ai` 或 `general_ai`；显式启用且未超过每日次数/Token 预算时，有依据问题可返回 `ai`，低风险无依据问题可返回明确标注的 `general_ai`，External 失败安全降级。医疗诊断、政策资格、金额、办理材料等高风险问题无依据时拒绝猜测。
- `POST /api/public/items/{id}/view`：仅对仍为 `PUBLISHED` 的内容记录一次匿名浏览事件，不保存用户问题或身份信息。

助手请求示例：

```json
{
  "message": "最近有哪些反诈提醒？",
  "contextSlug": "guide-1"
}
```

`contextSlug` 可省略；从办事详情提问时用于优先匹配当前材料。响应 `data` 包含
`answer`、`actions`、`citations`、`disclaimer` 和 `mode`。每条引用包含
`title`、`slug`、`kind`、`category`、`sourceName`、`publishedAt` 和 `quote`。
找不到可靠依据时 `citations` 为空并明确返回“当前已发布内容中没有可靠答案。”

AI 服务内部接口：

- `POST /internal/assistant/answer`：接收用户问题和编号证据，返回短句回答、1—3 条行动
  建议、实际使用的引用编号、model、request_id、token 和耗时。仅在
  `ASSISTANT_EXTERNAL_ENABLED=true` 时可用；自动测试使用本地 Mock HTTP Server。
- `POST /internal/assistant/general-answer`：只处理无已发布依据的低风险通用问题，并在
  上层标注“通用 AI 参考”；高风险问题不会调用该接口。

平台运营接口（机构端路由 `/operations`，仅平台管理员可见）：

- `GET /api/operation-metrics`：仅 `PLATFORM_ADMIN`，返回来源启停及最近状态、今日发现/
  采集/重复/失败、AI 队列和 Token 预算、待审图片候选、待审/已发布内容、平均采集和
  AI 耗时、最近未解决错误及失败来源，并保留浏览、收藏、助手引用和人工修改率等运营
  聚合。读取时按日期幂等保存 `daily_operation_snapshot`，无数据返回 0。

本地服务文档与健康检查：

- Spring Boot Swagger：`http://127.0.0.1:8080/swagger-ui/index.html`
- Spring Boot OpenAPI：`http://127.0.0.1:8080/v3/api-docs`
- FastAPI 文档：`http://127.0.0.1:8001/docs`
- FastAPI 健康检查：`http://127.0.0.1:8001/health`
