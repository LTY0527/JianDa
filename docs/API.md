# API 说明

统一响应：`{ code, message, data, timestamp }`。公开接口无需 JWT，其余接口使用 `Authorization: Bearer <token>`。

## 认证

- `POST /api/auth/login`：账号密码登录。
- `GET /api/auth/me`：当前账号、角色与机构。

## 材料与处理

- `POST /api/documents`，`POST /api/documents/{id}/upload`
- `GET /api/documents`，`GET /api/documents/{id}`
- `POST /api/documents/{id}/process`
- `GET /api/documents/{id}/jobs|segments|fields|generated`

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

## 用户端公开接口

- `GET /api/public/items|categories|search`
- `GET /api/public/items/{slug}`
- `POST|DELETE /api/public/items/{id}/favorite`
- `GET /api/public/assistant/suggestions`：根据当前已发布分类返回稳定推荐问题。
- `POST /api/public/assistant/chat`：仅检索 `PUBLISHED` 内容并返回回答、来源引用和安全提示。

助手请求示例：

```json
{
  "message": "最近有哪些反诈提醒？",
  "contextSlug": "guide-1"
}
```

`contextSlug` 可省略；从办事详情提问时用于优先匹配当前材料。响应 `data` 包含 `answer`、`citations` 和 `disclaimer`。每条引用包含 `title`、`slug`、`kind`、`category`、`sourceName`、`publishedAt` 和 `quote`。找不到可靠依据时 `citations` 为空，回答不会补充未发布事实。

本地服务文档与健康检查：

- Spring Boot Swagger：`http://127.0.0.1:8080/swagger-ui/index.html`
- Spring Boot OpenAPI：`http://127.0.0.1:8080/v3/api-docs`
- FastAPI 文档：`http://127.0.0.1:8001/docs`
- FastAPI 健康检查：`http://127.0.0.1:8001/health`
