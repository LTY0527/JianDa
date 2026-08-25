# Phase 9.9 基线审计

日期：2026-08-24  
分支：`feat/phase9-9-commercial-regional-v1`  
起始提交：`0dc8db3`

## 继承基线

- Phase 9.8.4 的 AI、PDF/OCR、采集、真实图片、H5、机构端和居民治理八项 REAL Gate 均为 PASS。
- 接管时 Git 工作区干净；未修改或删除 Phase 7.3 历史截图。
- Docker 的 MySQL、AI、Backend、Frontend 四服务均为 healthy，四个健康地址 HTTP 200。
- 接管时 MySQL 有 5 个来源、28 个 PUBLISHED 展示项和 13 个 `is_demo=true` 帖子；可见数据混入演示种子是 Phase 9.9 P0。

## 已确认缺口

1. H5 助手请求没有携带动态居民 token；后端仍按全局/居民/游客每日 call/token 限额阻断。
2. 首页把“大场”当主题频道；五个快捷入口均落到同一服务页。
3. 地区选择只有大场可用，顾村、庙行未接入。
4. `DemoDataInitializer` 在真实 MySQL 重启时持续补写演示文章和帖子。
5. 同一 `xxgk.shbsq.gov.cn` 域名受唯一约束限制，无法登记三个镇的独立栏目。
6. “立即检查”结果仍位于来源长页，尚未形成独立进度和候选页。
7. SaaS 授权、可信服务、订单和支付 Provider 仍未形成完整业务模型。

## 官方入口验证

| 地区 | URL | 结果 | 页面标题 |
| --- | --- | --- | --- |
| 顾村镇 | `https://xxgk.shbsq.gov.cn/infoDirectory.html?type=dept&dept=003005` | HTTP 200 / 38569 bytes | 信息公开目录_上海市宝山区人民政府门户网站 |
| 大场镇 | `https://xxgk.shbsq.gov.cn/infoDirectory.html?dept=003006&rn=大场镇&type=dept` | HTTP 200 / 38081 bytes | 信息公开目录_上海市宝山区人民政府门户网站 |
| 庙行镇 | `https://xxgk.shbsq.gov.cn/infoDirectory.html?dept=003007&rn=庙行镇&type=dept` | HTTP 200 / 38444 bytes | 信息公开目录_上海市宝山区人民政府门户网站 |

提示词给出的三个九位 region code 已作为试点内部 scope 使用；国家统计局旧版城乡代码静态页面在本次网络环境返回 404，因此“官方代码网页二次核验”保持待人工核验，不冒充已完成。

## 安全边界

- 未输出 `.env`、API Key、Authorization 或 Bearer。
- 未执行 reset、restore、clean、stash、push、`docker compose down -v`。
- 未删除 MySQL volume、真实上传或真实网页文章。

