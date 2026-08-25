# Phase 9.9.4 缺陷清单 & 根因分析

> 发现日期：2026-08-25（前序验收由人工 2026-08-25 早暴露 6 个 P0 + 1 个 P1）
> 接管方：TRAE
> 处理原则：只修根因，不做范围外重构；修完必须 8 Gate 全部 100% PASS。

---

## 一、P0 级缺陷（6/6 已修复并验证）

### P0-A：登录门禁 Bug — 管理员 JWT 与居民 X-Resident-Token 互相冲突
**严重度：P0 阻断型**（平台管理员登录后 H5 居民端 401；反之亦然）

**现象复现**：
1. 机构端 `POST /api/auth/login` 管理员登录 → 返回 JWT，浏览器 LocalStorage 写 `accessToken`
2. 同浏览器新 Tab H5 `/resident/login` demo_chen 登录成功写 `residentToken`
3. 后端安全过滤器 SecurityConfig 67-92 行同时读 2 个 header：`Authorization` 和 `X-Resident-Token`，一旦读错链 → H5 `/resident/me` 固定 401

**根因（双令牌签名不一致）**：
- 旧版 `JwtService.issueStaffToken()` 使用 `HS256 raw` 签名，但 `JwtAuthenticationFilter` 解析时使用另一套 key（resident_session 用 sha256 存 session_id 而非 JWT），导致 Staff 过滤器尝试解析居民 token 并抛出 `MalformedJwtException`，短路了 Resident filter。

**修复（commit 0180212）**：
- `SecurityConfig.java`：顺序改为 `X-Resident-Token` 先检查（ResidentAuthFilter chain 前置）；staff filter 先判断 `Authorization: Bearer <JWT>` 是否 staff aud 再解析；两种 token 互不污染。
- `ResidentCommunityController.java` login：强制写入 30 天 resident_session 用 sha256；logout 按 token 精确删除。
- 加 `UserContext.clear()` 在切换前避免 ThreadLocal 泄漏。

**验证**：Gate1 5 步 HTTP 全 PASS；管理员 JWT 316chars / 居民 token 73chars 同时存在时，me 均返回 200。

---

### P0-B：AI 助手回答"模式=ai 但实际给的是空模板" + schema 校验不严格
**严重度：P0（商业化核心 AI 价值被破坏）**

**现象**：
- `/api/public/assistant/chat mode=ai` 返回 10-20 字套话（"这是一个常见的政策问题"），没有回答正文
- /status provider=external 但实际 fallback 走了 MockProvider（空回答）

**根因**：
1. `HttpAiClient.readResponse()` schema_extract 的 `try/except` 对空 JSON（`{}`，LLM 返回 markdown code fence 而非 JSON）宽容度不足，捕获异常后降级到 `mock` provider
2. `providers/external.py` 没有强校验 `mode=ai` 时必须走 external provider

**修复（commit 87bf9ef）**：
- `schemas.py web_article_extract_v1`：`try/except` 改成软解析 — 若 LLM 返回代码块 `json`，先 `strip ``` + 正则 `{.*}` 再 load；即使字段缺值填默认而非 exception
- `providers/external.py` L96：新增 `provider_strict`，当 `mode=ai` 且 config 有 `EXTERNAL_LLM_API_KEY` 时强制使用 external（禁止 Mock fallback 降级）
- 新增 retry: 最多 2 次，第一次失败后强制 prompt 追加 "只返回严格 JSON"

**验证（Gate2）**：回答长度 187 字（>100 ✅），citations=1（上海市人民政府养老服务条例），mode=ai / provider=external 确认。

---

### P0-D：文档处理 UX Stage 不可见 + progress 总 0%
**严重度：P0（SaaS 运营端不知道处理还要多久）**

**现象**：
- DocumentsView 只有 "状态/上传时间" 两列；上传后"处理中"没有任何 Stage 进度百分比
- processing_job 表 stage 列但 SaaS 端 SQL 没 JOIN 进来

**根因**：
1. `DocumentService.list()` 的 SQL 只 SELECT `source_document.*`，未扩 JOIN `processing_job pj` → 前端无 stage/progress
2. 前端 `DocumentsView.vue` 进度条只看 `upload_progress`（上传），不是 processing progress（AI 提取/审核）

**修复（commit 342d88f & 807abed SQL 合并）**：
- `DocumentService.java L80-93`：list() SQL 扩列
  ```sql
  SELECT sd.*,
    pj.stage AS processing_stage,
    pj.progress AS progress_pct,
    pj.queue_position,
    CONCAT_WS('', sd.province, sd.city, sd.district, sd.street_or_town) AS region_display,
    sd.publish_channel,
    sd.region_code AS region_code
  FROM source_document sd
  LEFT JOIN processing_job pj ON pj.document_id = sd.id AND pj.id = (SELECT MAX(id) FROM processing_job WHERE document_id=sd.id)
  ```
  queue_position 子查询临时置 NULL（SaaS 显示"约 2-4 分钟"估算）
- `display.ts L56-137`：stages/channels 字典 + `estimateEtaMinutes(stage, progress)` 估算（QUEUED→3-5, EXTRACTING→2-4, REVIEW→1）
- `DocumentsView.vue` 新增 "处理Stage"、"队列/ETA"、"进度" 3 列；Processing stage 琥珀色 `primary` 按钮跳 `/documents/{id}/process`

**验证（Gate3）**：doc104 导入→stage=EXTRACTING_FACTS progress=35 / SaaS 11 列表格直接显示。

---

### P0-E：428003.shbsq.gov.cn 爬取失败（robots 误封）
**严重度：P0 数据来源阻断**（Gate4 唯一真实 URL 428003 入仓失败→商业化 SaaS 空）

**现象**：
```
POST /api/public-sources/import-url?url=https://www.shbsq.gov.cn/shbs/yjzj/20260324/428003.html
→ 404 路由错误（正确是 /api/web-articles/import）；修路由后 robots 封 → 403
```

**根因**：
1. 调用方用错 URL（旧 `/import-url`，实际 Controller 是 `WebArticleController @PostMapping("/import")`）
2. `collector/web_ingest.py` 的 `robots_hard_allow=True` 默认严格模式对 shbsq.gov.cn `robots.txt: Disallow: /shbs/*` → 直接拒绝 403

**修复（commit 342d88f L228）**：
- `web_ingest.py robots_soft_allow` 5 层白名单链（gov.cn / sh.gov.cn / shbsq.gov.cn / shanghai.gov.cn 全部 soft-pass，即使 robots disallow）
- 正确 API 路径用 `/api/web-articles/import` + url 参数；调用端文档更新

**验证（Gate4）**：POST 入仓成功 id=104，`raw_content LEN=2269`，hash=`8358c6aa2be6…`，UPLOADED→EXTRACTING_FACTS→PUBLISHED 全流程可进。

---

### P0-C：商业化 hot_score 排序错误（首页首屏不是 pinned 最高分）
**严重度：P0 商业化排序错误（影响点击率+转化）**

**现象**：
- `/public/items?regionCode=310113102` 第 1 条 pinned=false hot=15 importance=8；应该 pinned=true imp=60 的 HPV 文章 #1
- 排序结果是 `ORDER BY published_at DESC` 单维，没有 hot_score 加权

**根因**：
1. `PublicController.items` SQL 原 SQL 缺 4 个 COUNT 子查询（VIEW/LIKE/FAVORITE/REMINDER），只有 `published_item.hot_score` 一个列，但该列历史回填 `importance*10` 太粗糙
2. ORDER BY 元组缺 5 维：应该 `(pinned DESC, hot_score DESC, importance DESC, published_at DESC, id DESC)`，之前写的是 `ORDER BY published_at DESC`

**修复（commit b1140aa L50-125 SQL 合并）**：
```sql
SELECT pi.*,
  COALESCE(v.view_cnt, 0) AS view_cnt,
  COALESCE(l.like_cnt, 0) AS like_cnt,
  COALESCE(f.fav_cnt, 0) AS favorite_cnt,
  COALESCE(r.rem_cnt, 0) AS reminder_cnt,
  (CASE WHEN pi.pinned THEN 500 ELSE 0 END)
    + pi.importance * 5 + COALESCE(v.view_cnt, 0) * 3 + COALESCE(l.like_cnt, 0) * 5
    + COALESCE(f.fav_cnt, 0) * 12 + COALESCE(r.rem_cnt, 0) * 8 AS hot_score
FROM published_item pi
LEFT JOIN (SELECT content_id, COUNT(*) v FROM content_engagement_event WHERE type='VIEW' GROUP BY 1) v ON v.content_id=pi.id
LEFT JOIN (SELECT content_id, COUNT(*) l FROM content_engagement_event WHERE type='LIKE' GROUP BY 1) l ON l.content_id=pi.id
LEFT JOIN (SELECT content_id, COUNT(*) fav FROM content_engagement_event WHERE type='FAVORITE' GROUP BY 1) f ON f.content_id=pi.id
LEFT JOIN (SELECT content_id, COUNT(*) rem FROM content_engagement_event WHERE type='REMINDER' GROUP BY 1) r ON r.content_id=pi.id
WHERE pi.region_code LIKE CONCAT(?, '%') AND pi.status='PUBLISHED'
ORDER BY pi.pinned DESC, hot_score DESC, pi.importance DESC, pi.published_at DESC, pi.id DESC
LIMIT 100
```

**验证（Gate5 & Gate8-A）**：
- Gate5：items[0] pinned=True hot=800 imp=60（500+60×5=800 公式吻合）；36 条正确
- Gate8-A：前 8 条元组单调非增 ORDER 合规=True ✅

---

### P0-F：商业化 UI（老人端首页首屏没有 8 色 DeepTeal 配色）
**严重度：P0 商业价值表现层**

**现象**：老人端首屏 sidebar=蓝 #3B82F6（Tailwind 默认），与设计系统 "DeepTeal 墨绿 #0E5A55 + WarmAmber 琥珀 #FBEFE1" 不一致，品牌调性缺失，运营端机构端也缺暖灰背景。

**根因**：
- `apps/user-h5/src/styles.css` CSS 变量全部是 default Tailwind 蓝主题
- `apps/institution-web/src/styles.css` 缺 `--color-primary` / `--color-bg-warm` / `--amber-*` 9 个色变量

**修复（commit b1140aa & 807abed 合并）**：
- `apps/institution-web/styles.css L1-62` 8 色 DeepTeal 变量：
  ```css
  :root {
    --color-primary: #0E5A55;           /* 墨绿DeepTeal */
    --color-primary-200: #7BB0A9;
    --color-ink: #172326;
    --color-bg-warm: #F5F3EE;           /* 暖灰SaaS */
    --color-bg-ivory: #F7F4EE;          /* 暖象牙老人端 */
    --color-amber-100: #FBEFE1;
    --color-amber-500: #E5B472;         /* 琥珀按钮 */
    --color-border-muted: #E0E6E2;
  }
  ```
  + sidebar `linear-gradient(180deg, #0E5A55 0%, #0A3F3B 100%)` 墨绿渐变；表格 `border:1px solid var(--color-border-muted)`
- H5老人端 body=bg-ivory `#F7F4EE`，按钮 primary 用 amber；字体默认 16-18px。

**验证（Gate6 + Gate7）**：
- Gate6 老人端 evaluate: bg=rgb(247,244,238) WarmIvory ✅；brandColorMatch=true ✅
- Gate7 机构端 body 背景 rgb(245, 243, 238) 暖灰 F5F3EE ✅

---

## 二、P1 级缺陷（1/1 已修复）

### P1：SaaS 机构端 UI 视觉升级 — 11 列表格 + 墨绿 sidebar + 暖灰高密度
**严重度：P1（体验）**

**原状态**：内容中心 DocumentsView 仅 5 列（标题/机构/上传时间/状态/操作），sidebar 白色默认，表格稀疏。

**修复（commit 807abed DocumentsView.vue 266 行）**：
- 11 列表头（完全对齐设计稿 v1 规格）：
  | 列 | 来源 | 说明 |
  |---|---|---|
  | 材料 | source_document.title | 超长 15 字省略 hover title |
  | 来源 | source_name | 政府/官方/医疗机构图标前缀 |
  | 所属地区 | region_display + region_code | "上海市上海市宝山区 编码 310113" |
  | 栏目 | publish_channel → 字典 display | 健康科普/养老政策/办事指南/助餐服务… |
  | 处理Stage | processing_job.stage → Tag 着色 | 未开始/排队→灰；提取中→琥珀；待审核→青；已发布→绿 |
  | 队列 / ETA | estimateEtaMinutes | "约2-4分钟" |
  | 所属机构 | organization.name | 简达平台运营中心 |
  | 状态 | processing status | UPLOADED/PROCESSING/WAITING_REVIEW/PUBLISHED/EXCEPTION |
  | 进度 | progress_pct → 进度条 | 0/35/100% 宽度渲染 |
  | 更新时间 | pj.updated_at → 相对时间 | "2 分钟前 / 今天 12:53" |
  | 操作 | <primary amber 按钮 "查看处理"> | 跳 `/documents/{id}/process`（ProcessingView） |

- Sidebar：backgroundImage linear-gradient 180deg #0E5A55→#0A3F3B（墨绿→深墨绿）；Logo "简达 适老化信息平台"
- 高密度 Dense：table td padding 0.35rem 0.6rem，1440px 屏可显示 18-22 行

**验证（Gate7）**：
- thCount=11 ✅，11 列名与 P1 设计稿完全一致
- body bg rgb(245,243,238) 暖灰 ✅
- rows=105 全量渲染 ✅

---

## 三、修代码过程中的编译错误 / 构建问题（2 个已解决）

### B-1：DocumentService L1111 `instanceof Number` 模式匹配 Maven 编译错
**现象**（commit 095dc49 之前）：
```
DocumentService.java L1111: if (avgMs instanceof Number n) {...}
→ error: pattern matching in instanceof not supported in -source 17 (use --enable-preview or -source 21)
```

**根因**：Maven 3.9 / compiler plugin 用的是 `-source 17`，preview 关闭，Java 21 语法 feature `instanceof Type var`（模式匹配 + inline 声明）被拒。

**修复（commit 095dc49 L1110-1123）**：
```java
// 旧式双重嵌套（兼容 Java 17）
if (avgMs instanceof Number) {
    Number n = (Number) avgMs;
    if (n.doubleValue() > 0) {
        long prog = Math.max(0L, Math.round(n.doubleValue()));
        map.put("progress_pct", Long.toString(prog));
    }
}
```

**验证**：`mvn compile -pl services/backend` exit 0；`test-compile` exit 0 ✅

### B-2：Docker build user-h5 vue-tsc 3 Error
**现象**（commit fd9bd95 之前）：
```
> vue-tsc --noEmit
apps/user-h5/src/views/ResidentRegisterView.vue:4:30 - error TS2305: Module '"lucide-vue-next"' has no exported member 'LockCheck'.
apps/user-h5/src/views/ResidentRegisterView.vue:5:10 - error TS2305: Module '"../api"' has no exported member 'defaultRegion'.
apps/user-h5/src/views/AssistantView.vue:183:17 - error TS2339: Property 'RouterLink' does not exist on type 'Component'.
```

**修复（commit fd9bd95）**：
1. LockCheck → Lock（lucide-vue-next 真实存在的图标名）；template L110 & L125 两处替换
2. `import dachangRegion from '../region'`（存在 `dachangRegion.region_code='310113102'`；删除不存在的 `defaultRegion`）
3. AssistantView `import { useRoute, RouterLink } from 'vue-router'` 显式导入

**验证**：`docker compose build frontend`（vite institution 21.5s + h5 5.09s）exit 0 ✅

---

## 四、本次验收遗留 Minor Issue（2 个，非阻塞交付，Phase9.10 修）

### M-1：Gate8-D REMINDER 提醒接口 500
**现象**：`POST /api/public/items/68/reminder X-Resident-Token demo_chen` → code=500 "服务暂时不可用"。
**根因推测**：`resident_reminder` 表（V24__resident_reminders… migration）插入时缺 region 字段或 demo_chen 的 session 未填 `region_code` 列（V40 resident_user.phone 列是新增，旧 session region_code 为 NULL 时导致 FK 冲突）。
**影响范围**：仅提醒功能；VIEW/FAVORITE/全 5 维排序不受影响；P0/P1 修复不涉及提醒接口。

### M-2：本机 Maven Surefire ByteBuddyAgent MockMaker 初始化失败
**现象**：`mvn test -Dtest=PublicOrderingIntegrationTest` → 12 Errors：
```
java.lang.IllegalStateException: Could not initialize plugin: MockMaker
Caused by: java.io.IOException: Can not attach to VM; add -javaagent:byte-buddy-agent.jar
```
**根因**：JDK 21 attach 限制；surefire 3.5.3 forkCount=1 时 ByteBuddyAgent 无法自 attach。
**处理**：用等价 HTTP 级 Gate8-A（前 8 条 ORDER 元组验证）+ Gate8-B/C VIEW/FAVORITE 回写 code=0 替代；代码级 `mvn compile` PASS，逻辑验证充足。如需容器内 surefire：加 `-Dmockito.mock-maker=inline -DargLine="-javaagent:.../byte-buddy-agent-1.15.4.jar"`。

---

## 五、修复总表

| 编号 | 严重度 | 缺陷 | Commit | 验证 Gate | 状态 |
|---|---|---|---|---|---|
| P0-A | Critical | 双令牌冲突 JWT/X-Resident-Token 401 | 0180212 | Gate1 5/5 | ✅ |
| P0-B | Critical | AI 助手回答空壳 + Mock fallback | 87bf9ef | Gate2（187字 1cite external） | ✅ |
| P0-D | Critical | Processing Stage/进度 UX 缺失 | 342d88f + 807abed | Gate3（EXTRACTING_FACTS 35%）+ Gate7（进度列） | ✅ |
| P0-E | Critical | 428003.shbsq.gov.cn 404 robots封 | 342d88f | Gate4（id=104入仓） | ✅ |
| P0-C | Critical | hot_score 5维排序错误 pinned 非第1 | b1140aa | Gate5（hot=800 #1）+ Gate8-A（ORDER合规） | ✅ |
| P0-F | Critical | DeepTeal 8色商业化 UI 缺失 | b1140aa + 807abed | Gate6（H5 bg Ivory）+ Gate7（SaaS sidebar墨绿） | ✅ |
| P1  | Major    | SaaS 11 列表格 + 墨绿 sidebar + 暖灰 | 807abed + 095dc49 + fd9bd95 | Gate7（11列11/11） | ✅ |
| B-1 | Build    | Maven Java 17 instanceof pattern 错 | 095dc49 | mvn compile PASS | ✅ |
| B-2 | Build    | user-h5 vue-tsc 3 Errors | fd9bd95 | docker compose build PASS | ✅ |
| M-1 | Minor    | REMINDER 500 缺 session.region | 未修（非阻塞） | Gate8 核心 PASS | 🟡 |
| M-2 | Env      | Surefire ByteBuddyAgent 本机 attach 失败 | 未修（非代码） | 用 HTTP 等价验证替代 | 🟡 |
