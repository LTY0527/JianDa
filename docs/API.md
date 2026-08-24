# API 说明

统一响应：`{ code, message, data, timestamp }`。公开接口无需 JWT，其余接口使用 `Authorization: Bearer <token>`。

## 认证

- `POST /api/auth/login`：账号密码登录。
- `GET /api/auth/me`：当前账号、角色与机构。

## 材料与处理

- `POST /api/documents/metadata-preview`：上传 PDF/PNG/JPG 后预识别标题、来源、文号和材料内权威证据；不创建正式文档或处理任务。
- `POST /api/documents`，`POST /api/documents/{id}/upload`：上传响应及后续详情包含
  `extraction_method`，取值为 `pymupdf`、`ocr`、`pymupdf+ocr`、`manual`、
  `manual_required` 或 `unknown`，机构端据此说明正文来自文本层、扫描页 OCR 或人工录入。
- `GET /api/documents`，`GET /api/documents/{id}`
- `POST /api/documents/{id}/process`：创建或复用后台处理任务并立即返回
  `documentId`、`jobId`、`status`、`stage` 和 `progress`；真实 AI 在后台执行。
- `GET /api/documents/{id}`：除材料详情外返回最近处理任务、阶段、进度、章节、Token、
  耗时、缓存与失败信息，供处理页轮询和刷新恢复。
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
- `POST /api/source-registries/{id}/discover-jobs`：创建一次有界异步发现任务；只发现候选 URL，不创建材料、不调用 AI，并返回用于独立进度页的 job。
- `GET /api/source-registries/discover-jobs/{jobId}`：查询连接、栏目读取、文章识别、去重和结果整理进度及最终候选。
- `POST /api/source-registries/{id}/shadow`：抓取指定候选并返回正文、封面策略和图片候选预览；不创建材料、不调用 AI、不发布。
- `POST /api/source-registries/{id}/collect`：确认指定候选后创建材料并进入 `WAITING_APPROVAL`；不会自动审核或发布。
- `POST /api/source-registries/{id}/collect-batch`：请求体为已勾选的 canonical URL 列表；
  只保存所选未重复内容并加入 AI 等待队列，不自动审核或发布。
- `POST /api/source-registries/quick-preview`：安全预览未知官方 URL，返回 canonical、域名、
  robots、页面标题和网站/公众号身份，不创建来源或材料。
- `POST /api/source-registries/quick-confirm`：仅平台管理员确认官方性质、保存来源身份并按
  选定模式继续导入；微信公众号使用账号主体、biz 和指纹，不按共享域名合并不同账号。
- `GET /api/crawl-tasks`、`GET /api/crawl-tasks/{id}`：按状态/来源查看任务计数、阶段、错误摘要和逐条错误队列。
- `POST /api/crawl-tasks`：创建采集任务。
- `POST /api/crawl-tasks/{id}/cancel`：取消仍可取消的任务。
- `POST /api/crawl-tasks/errors/{errorId}/retry`：只重试指定、未解决且可重试的错误。
- `POST /api/crawl-tasks/{id}/retry-failures`：确认后批量重试该任务仍可重试的失败项。

### Phase 9.3 图片候选与内容版本

- `GET /api/web-articles/{documentId}/image-candidates`：查看候选 URL、来源页、来源名、发现方式、alt、尺寸、MIME、hash、缓存、权利和审核状态。
- `POST /api/web-articles/image-candidates/{candidateId}/approve`：请求体为 `sourceName`、`usageBasis`；二者均不能为空。
- `POST /api/web-articles/image-candidates/{candidateId}/reject`：记录拒绝原因并安全回退分类默认图。
- `POST /api/cover-backfill/preview`：仅平台管理员；按缺失封面、来源、内容类型、发布状态
  和日期预览历史网页/PDF/图片范围，不修改材料。
- `POST /api/cover-backfill/execute`：仅平台管理员；最多处理本次预览规则命中的 100 条，
  返回扫描、公开封面更新、候选新增、策略自动确认和失败明细。网页图片只有来源明确允许
  缓存且策略已审核时才下载公开；PDF 第一页和机构上传图片可作为本地可追溯封面。
- `GET /api/public/items/{slug}/cover`：读取已审核本地封面，返回正确图片 MIME、ETag 和
  30 天公开缓存头，不暴露磁盘路径。
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
- `GET /api/public/regions`：返回当前已开放的区域；当前试点为大场镇 `310113102`、顾村镇
  `310113109`、庙行镇 `310113112`。
- `GET /api/public/items?regionCode=310113102`：优先返回指定试点区域的已发布内容，并保留
  允许公开的全局内容；不会返回未审核或未发布材料。
- `GET /api/public/items/{slug}`
- `GET /api/public/items/{slug}/original-file`：仅已发布且发布时显式设置 `allowPublicOriginal=true` 的材料可用；同样支持字节范围读取和内容 SHA-256 校验。
- `POST|DELETE /api/public/items/{id}/favorite`
- `GET /api/public/assistant/suggestions`：根据当前已发布分类返回稳定推荐问题。
- `POST /api/public/assistant/chat`：状态问题由后端直接回答；公共服务问题通过可替换检索器仅召回 `PUBLISHED` 内容，并返回回答、行动建议、来源引用、安全提示和 `mode`。`mode` 为 `status`、`retrieval`、`ai` 或 `general_ai`；显式启用且未超过每日次数/Token 预算时，有依据问题可返回 `ai`，低风险无依据问题可返回明确标注的 `general_ai`，External 失败安全降级。医疗诊断、政策资格、金额、办理材料等高风险问题无依据时拒绝猜测。
- 助手响应可包含 `factCards`，类型为 deadline/location/phone/fee/material，只从已确认或人工修正字段生成。External 回答中的日期、时间、电话和金额必须被实际引用 quote 覆盖，否则整体安全回退为 retrieval。
- `POST /api/public/items/{id}/view`：仅对仍为 `PUBLISHED` 的内容记录一次匿名浏览事件，不保存用户问题或身份信息。
- `GET /api/public/service-directory?regionCode=310113102`：只聚合当前区域已审核发布内容中的真实地点、电话、时间与官方来源；缺失字段不返回伪造兜底。
- `GET /api/public/reminders`、`POST /api/public/items/{id}/reminder`、`DELETE /api/public/reminders/{id}`：按匿名游客 ID 保存、读取和删除内容时间提醒。
- `POST /api/public/items/{id}/event/{eventType}`：记录收听、服务电话点击和地址复制等最小匿名使用事件；不采集精确位置和浏览器指纹。

居民与邻里接口：

- `POST /api/public/resident/login`、`GET /api/public/resident/me`、`POST /api/public/resident/logout`：居民试点会话；后续请求使用 `X-Resident-Token`，服务端仅保存 token 的 SHA-256 摘要。
- `GET|POST /api/public/community/posts`：按大场镇区域和分类读取或发布不超过 500 字的纯文字帖。
- `POST /api/public/community/posts/{id}/like`：切换点赞。
- `GET|POST /api/public/community/posts/{id}/comments`：读取或发布不超过 300 字的评论。
- `POST /api/public/community/posts/{id}/report`：提交举报并将帖子进入 `REPORTED` 待核对状态。
- `GET /api/community-admin/posts`、`POST /api/community-admin/posts/{id}/status`：仅平台管理员查看举报并在 `VISIBLE/REPORTED/HIDDEN` 间治理。

居民 DEMO 账号只用于本地产品验收。后端只接受当前三个已开放地区的社区写操作，并按
`regionCode` 隔离 Feed；帖子不支持私信或精确门牌。

## Phase 9.9 商业化边界接口

- `GET /api/public/commercial/services?regionCode=`：仅返回当前地区 `VERIFIED + ACTIVE` 的可信服务；合作服务不冒充政府事项。
- `GET /api/public/commercial/sponsors?regionCode=`：最多返回一个当前有效的 ACTIVE 合作/公益位，并携带明确标签。
- `GET|POST /api/public/commercial/orders`：居民查询本人订单或为真实服务产品创建 `PENDING_PAYMENT` 订单。
- `POST /api/public/commercial/orders/{id}/cancel`：仅取消本人的待支付订单。
- `POST /api/public/commercial/orders/{id}/refund`：仅对允许退款的已支付状态提交申请。
- `GET /api/public/commercial/payment-capabilities`：返回 Provider 可用性；当前无商户凭据时为 `available=false`。
- `GET /api/commercial/overview`：仅平台管理员读取套餐、授权、服务商、商品、赞助、订单、退款与支付能力真实计数。

支付由 `PaymentProvider` 抽象承载。当前 `DisabledPaymentProvider` 对未配置的创建支付或退款
请求返回明确不可用状态，不生成支付成功事件。

## Phase 9.9.2 新增接口

- `PUT /api/documents/{id}/region-scope`：平台管理员修正已核验材料的地域范围。
- `POST /api/source-registries/{id}/collect-batch`：异步加入选中候选，返回 HTTP 202 和 jobId。
- `GET /api/source-registries/import-jobs/{jobId}`：读取批量任务进度、成功、重复、失败和当前条目。
- `GET /api/documents/{id}/processing-snapshot`：处理页轻量轮询；不返回 raw_text、segments、fields 或 generated 正文。
- `GET /api/public/membership/plans`：读取已启用周/月/年会员套餐。
- `GET /api/public/membership/capabilities`：返回 Demo 开关和真实支付可用性，不返回凭据。
- `GET /api/public/membership/me`：读取当前居民 Demo 会员状态。
- `POST /api/public/membership/demo-payments`：创建支付宝/微信 Demo 会话；仅返回 DEMO 状态和本地二维码 payload。
- `POST /api/public/membership/demo-payments/{id}/confirm`：确认课堂 Demo，不生成真实 PAID。

批量任务与处理 snapshot 都要求登录并校验组织/平台权限。会员 Demo 不能作为真实支付凭证；生产关闭 Demo 后，无商户 Provider 时明确返回不可用。

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

Phase 9.6 地区同步与调度接口（均要求平台管理员）：

- `POST /api/web-articles/{documentId}/region/sync`：按文档关联的来源登记同步省、市、区、街镇、`region_code` 和 `local_scope`；已发布内容同步更新，不接受调用方直接提交任意地区值。
- `PUT /api/source-registries/{id}/auto-crawl-enabled`：单独启停来源自动采集；来源总开关和全局 scheduler 开关仍同时生效。
- `POST /api/crawl-tasks/scheduler/sources/{sourceId}/run-now`：人工触发与生产 scheduler 相同的执行服务，用于受控验收；继续执行 lease、robots、SSRF、限速、条数、预算、重试和去重门禁。

生产 scheduler 只处理 `enabled=true`、`allow_auto_crawl=true` 且到期的来源。采集结果进入 AI 审批队列，不自动 AI、审核或发布。

本地服务文档与健康检查：

- Spring Boot Swagger：`http://127.0.0.1:8080/swagger-ui/index.html`
- Spring Boot OpenAPI：`http://127.0.0.1:8080/v3/api-docs`
- FastAPI 文档：`http://127.0.0.1:8001/docs`
- FastAPI 健康检查：`http://127.0.0.1:8001/health`
