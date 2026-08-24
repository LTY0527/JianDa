# Phase 9.9 检查清单

状态：`DONE / PARTIAL / BLOCKED / TODO`。

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| Phase 9.8.4 基线恢复 | DONE | PHASE9_9_BASELINE_AUDIT.md |
| 居民身份随 Assistant 请求传递 | DONE | api.ts + AssistantExternalIntegrationTest |
| 取消每日 call/token 业务阻断 | DONE | 30 次居民集成测试通过 |
| 首页移除“大场”频道、增加助餐 | DONE | HomeView.vue |
| 五个快捷入口独立路由 | DONE | ServiceChannelView.vue |
| 服务底栏改名 | DONE | BottomNav.vue |
| 大场/顾村/庙行地区选择与示意地图 | DONE | region.ts + H5Header.vue |
| 地区切换重载首页/服务/邻里/助手 | PARTIAL | 首页/服务/邻里已完成；搜索独立 scope 待补 |
| 精确清理真实环境模拟内容 | DONE | data cleanup preview/result |
| 顾村/庙行官方来源与 12h Scheduler | DONE | V32 + 官方 URL HTTP 200 |
| 各地区 8~10 篇真实发布精品 | TODO | 当前仅大场 2，禁止用假数据补数 |
| 独立采集进度与候选结果页 | DONE | PHASE9_9_CRAWL_UX_REPORT.md |
| 发布后返回工作台和下一篇 | DONE | PublishView.vue + Playwright |
| B 端 SaaS、服务、订单、支付边界 | DONE | V33 + 商业/支付报告；真实支付保持 BLOCKED |
| Phase 9.9 REAL Browser | DONE | 2/2，无 API 拦截；PHASE9_9_REAL_ACCEPTANCE_REPORT.md |
| 来源数与真实内容规模 | PARTIAL | 7 个来源、16 PUBLISHED，未达到 10 / 35～50 |
| 顾村与庙行本地精品 | TODO | 两镇 LOCAL 当前均为 0，不用模拟内容补数 |
| External 30 问质量验收 | BLOCKED | 本轮无新的 External 数据发送授权 |
