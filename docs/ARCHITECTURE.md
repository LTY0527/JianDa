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
- `services/backend`：鉴权、RBAC、材料、公开来源、审核发布、文件与审计日志。
- `services/ai-service`：文本提取、结构化、通俗化、步骤、风险提示与追溯。
- `packages/shared-types`：前端共享领域类型。
- `packages/shared-ui`：CSS 设计 token。
- `fixtures`：离线权威公开信息测试数据。

## 公开信息采集链路

```text
FixtureCollector / ManualImportCollector
  -> ContentImportService（来源启用、域名白名单、URL/正文去重）
  -> source_document（保留原文、来源、导入方式和时间）
  -> MockProvider（按主题稳定生成）
  -> 人工字段确认与审核
  -> published_item
  -> 用户 H5
  -> 撤回后公开接口立即不可见
```

`ContentCollector` 是可替换接口。当前只实现确定性 fixture 与手工导入，不访问任意网站；未来真实采集器必须继续经过相同白名单、去重、审核和发布边界。

## 关键约束

原始文件与导入原文只读保留；生成内容版本化。所有机构业务查询必须带组织范围，平台管理员除外。公开来源接口仅允许平台管理员。发布必须存在审核记录。AI 不可用不会影响已发布公开内容。

本地服务统一使用 `127.0.0.1`。后端到 FastAPI 的内部回环连接显式绕过系统代理，避免开发机代理改写本地请求；真实外部模型仍由独立 Provider 和环境变量配置。

## 开发运行模式

后端默认使用 H2，正式环境切换 MySQL 8 与同一套 Flyway 迁移。AI 默认 `MockProvider`。两个前端读取真实后端接口，并对加载失败、空状态和权限错误给出明确提示，不以静态数据伪装成功。

AI 服务最低支持 Python 3.11，当前已在 Python 3.13 上完成本地验证；Python 3.9 不受支持。容器继续使用 Python 3.11 基线，本地开发可以选择任意 Python 3.11+ 解释器。
