# Phase 9.6 助手评估

日期：2026-08-23

## 已完成升级

- 检索结果携带 document id，并继续只召回 `PUBLISHED` 内容。
- `CONTACT`、`LOCATION`、`MATERIAL`、`FEE`、截止日期等已确认/人工修正字段生成“已核对关键信息” factCards。
- External 回答返回后确定性检查日期、时间、电话和金额是否被实际引用覆盖。
- 证据外事实触发 retrieval 安全回退；不会把 Provider 的无依据电话直接返回用户。
- H5 明确区分“平台运行状态”“原文检索”“已审核内容 + AI 整理”“通用 AI 参考”。

## 自动回归

- 后端助手定向集成：4/4 passed。
- 隔离 Playwright：3/3 passed。
- 幻觉样例：证据中不存在的 `021-12345678` 被事实覆盖校验拒绝。
- factCards 页面展示、无证据拒答和模式标签均通过。

## 真实 External 评估

- 真实问题数：0。
- 原因：本轮没有获得把多篇已发布证据再次发送给 External Provider 执行 10+ 问题集的明确授权。
- 因此真实引用正确率、平均耗时和真实助手 token 不填写估算值；助手 Real Gate 为 BLOCKED。
- 文档 63 的网页处理 External 数据只能证明采集处理链路，不冒充助手问答验收。
