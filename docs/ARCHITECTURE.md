# 系统架构

```text
institution-web (Vue 3, :5173) ─┐
                                ├─> backend (Spring Boot, :8080) ─> MySQL/H2
user-h5 (Vue 3, :5174) ─────────┘              │
                                               └─> ai-service (FastAPI, :8001)
```

## Monorepo

- `apps/institution-web`：机构端与平台运营端，以角色控制菜单和数据范围。
- `apps/user-h5`：移动优先公开阅读端。
- `services/backend`：鉴权、RBAC、材料、审核发布、文件与审计日志。
- `services/ai-service`：文本提取、结构化、通俗化、步骤与追溯。
- `packages/shared-types`：前端共享领域类型。
- `packages/shared-ui`：CSS 设计 token。
- `fixtures`：离线公开资讯与稳定 Mock AI 数据。

## 关键约束

原始文件与原文只读保留；生成内容版本化。所有机构业务查询必须带组织范围，平台管理员除外。发布必须存在审核记录。AI 不可用不会影响已发布公开内容。

## 开发运行模式

后端默认 `dev` 配置使用 H2，正式环境切换 `prod` 使用 MySQL 8 与 Flyway。AI 默认 `MockProvider`。两个前端在后端不可用时会使用固定演示数据，便于独立展示视觉与交互。

AI 服务最低支持 Python 3.11，当前已在 Python 3.13 上完成本地验证；Python 3.9 不受支持。容器继续使用 Python 3.11 基线，本地开发可以选择任意 Python 3.11+ 解释器。
