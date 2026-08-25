# Phase 9.7 基线审计

日期：2026-08-23

## Git

- 基线分支：`feat/phase9-6-real-acceptance-v1`
- 基线提交：`6a868e6`
- 工作分支：`feat/phase9-7-final-commercial-polish-v1`
- Phase 9.6 基线与远端同步。
- 工作区仅保留 15 张用户既有 `artifacts/phase7-3` 修改截图；本阶段不暂存、不覆盖、不删除。
- `.env` 已由 `.gitignore` 忽略且未被 Git 跟踪。

## 运行基线

- Docker MySQL、FastAPI、Spring Boot、frontend 四服务均为 healthy。
- 用户端：`http://127.0.0.1`
- 机构端：`http://127.0.0.1:8090`
- Phase 9.6 REAL Browser：2 passed / 2 skipped；skip 原因是当前进程没有注入平台管理员和居民验收密码。
- External Provider 已配置，密钥值未读取、未输出、未提交。

## 产品基线

- H5 已有真实大场镇内容、真实官方封面、五项一级导航、搜索转简达和事实卡。
- 首页仍由多个独立纵向模块组成，频道筛选、连续混合 Feed 和消费级首屏层级尚未收口。
- 机构端一级导航已收束为五项，但“采集与来源”默认页仍暴露较多专业能力和操作。
- HelpTip 与统一术语表尚不存在。

## 设计基准

当前会话没有内置 Image Gen 工具。按技能规则未改用需要额外 API Key 的 CLI；实现以 Phase 9.6 真实页面和 Phase 9.7 明确的结构、色彩、文案及交互规范为基准。AI 生成图不会用于文章真实配图。

## 基线结论

`DONE`：允许在不重建后端业务、不修改数据库迁移和不触碰历史截图的前提下进入 Phase 9.7。
