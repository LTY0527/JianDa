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

## 公开信息与用户端

- `POST /api/public-sources/import`，`GET /api/public-sources`
- `GET /api/public/items|categories|search`
- `GET /api/public/items/{slug}`
- `POST|DELETE /api/public/items/{id}/favorite`

后端启动后 OpenAPI：`http://localhost:8080/swagger-ui.html`。

