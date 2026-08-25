# Phase 9.8 官网采集与调度可靠性验收

日期：2026-08-24

## 验收环境

- Docker：MySQL、AI、Spring Boot、frontend 均为 healthy。
- Flyway：V30 已应用；未删除 volume，未执行 `docker compose down -v`。
- 本轮只执行低频只读核验与官方页面 GET；未绕过 robots、未批量保存、未调用 AI、未审核或发布。
- 当前容器非敏感配置：`CRAWL_SCHEDULER_ENABLED=false`。

## 已登记来源

真实 MySQL 中共有 5 个来源，未为凑数伪造第 6 个来源。

| ID | 域名 | 模式 | 自动采集 | 图片候选/缓存 | 历史结果 |
| ---: | --- | --- | --- | --- | --- |
| 1 | www.news.cn | MANUAL | 否 | 开启 | 21 jobs，16 成功；累计发现/新增 16/16 |
| 2 | mzj.gz.gov.cn | MANUAL | 否 | 开启 | 1 job，未形成成功闭环 |
| 3 | www.shanghai.gov.cn | MANUAL | 否 | 开启 | 4 jobs，3 成功；累计发现/新增 3/3 |
| 4 | www.szlhq.gov.cn | MANUAL | 否 | 开启 | 1 job，未形成成功闭环 |
| 5 | xxgk.shbsq.gov.cn | SECTION / SCHEDULED | 是 | 开启 | 6 jobs，6 成功；累计发现/新增 7/7 |

robots 实测：

- www.news.cn：`User-agent: *`、`Allow: /`。
- mzj.gz.gov.cn、www.shanghai.gov.cn、www.szlhq.gov.cn、xxgk.shbsq.gov.cn：`robots.txt` 返回 404，按 `ROBOTS_UNAVAILABLE` 处理；没有将 404 冒充明确允许，也没有绕过访问控制。

固定要求为至少 6 个已登记官方来源；当前仅 5 个，且其中两个来源没有成功闭环，因此不能判 PASS。

## Scheduler 证据

历史真实调度任务：

| Job | Source | 状态 | 触发 | 阶段 | 发现/新增/重复/失败 | 调度身份 | 开始 | 结束 |
| ---: | ---: | --- | --- | --- | --- | --- | --- | --- |
| 28 | 5 | SUCCESS | SCHEDULED | DISCOVERY | 0/0/0/0 | jianda-crawl-scheduler-v1 | 2026-08-23 13:23:44 | 2026-08-23 13:23:50 |
| 29 | 5 | SUCCESS | SCHEDULED | DISCOVERY | 3/3/0/0 | jianda-crawl-scheduler-v1 | 2026-08-23 13:25:39 | 2026-08-23 13:26:02 |

当前重部署后：

- backend 于 2026-08-23 23:53（Asia/Shanghai）启动并健康。
- source 5 的 `next_run_at` 仍为 2026-08-23 13:04:57，已经到期。
- 启动后没有新增 SCHEDULED job。
- 原因是当前容器 `CRAWL_SCHEDULER_ENABLED=false`；历史证据证明调度路径曾真实工作，但当前环境不能证明重部署后的自动触发。

## 大场镇真实内容盘点

MySQL 当前统计：

- WEB_ARTICLE：31。
- 大场/宝山相关：4。
- 已发布的大场/宝山相关：1。
- 目标要求：10+ 高价值本地内容；当前未达到。

官方宝山区门户已核验存在真实候选，例如：

1. 大场镇 2026 年政府开放日：居民可了解社区助餐、志愿积分兑换并参与规划交流。
2. 大场镇社区事务受理服务中心：包含地址、电话和服务时间。
3. 噪音扰民治理：包含实际整改措施与居民回访机制。

这些页面只作为真实候选证据；本轮没有未经批次确认直接保存、调用 AI 或发布。

## 错误、重试与副作用

- 已实现并回归的有界重试会保留来源级/全局上限、到期时间和 IMPORT/DISCOVERY 阶段。
- 本轮只读查询没有产生新材料、AI 请求或发布。
- 当前数据库聚合没有足够完整的六来源逐源 HTTP/robots/candidate/duplicate/duration/error/retry 矩阵；不能用历史成功总数替代缺失字段。

`CRAWL_REAL_ACCEPTANCE: PARTIAL`

原因：Scheduler 历史真实触发已证明，且大场来源存在成功任务；但当前 Scheduler 被禁用、来源仅 5 个、两来源无成功闭环、六来源矩阵和 10+ 本地内容均未达标。
