# 简达 Phase 9.9.4 交接清单 (HANDOFF MANIFEST)

- 仓库：https://github.com/LTY0527/JianDa.git
- 阶段：Phase 9.9.4 → 9.9.4_1 收口 (H5显示回归/登录门禁/Reminder500修复/交接收口)
- 基准分支：origin/main @ 3c1abcf
- 功能分支：feat/phase9-9-4-auth-ui-processing-v1
- 生成日期：2026-08-25
- 交接方式：Git 仓库 + 本 MANIFEST；**NON_GIT_HANDOFF_REQUIRED=NO** (无必须的二进制包)

---

## §A 必须入 Git 的文件（当前尚未 commit 的源码 / 文档 / migration）

### A.1 新增源码文件 (untracked → staged)
| # | 相对路径 | 说明 |
|---|---------|------|
| A1-1 | apps/user-h5/src/composables/useResidentAuth.ts | 居民登录门禁 composable：三态 unknown/authenticated/guest + bootstrap()真实校验/residentMe 401清理/ensureLogin redirect |
| A1-2 | services/backend/src/main/resources/db/migration/V41__resident_reminder_resident_user_column.sql | Flyway V41：resident_reminder 新增 resident_user_id BIGINT NULL + FK resident_user(id) ON DELETE CASCADE + idx_reminder_resident_user_time |

### A.2 已 tracked 但有源码修改的文件（git diff 可见，需随 3 commits 入 git）
| # | 相对路径 | 修改点 |
|---|---------|-------|
| A2-1 | apps/user-h5/src/views/ProfileView.vue | SFC 标准三层结构重排 script/template/style(scoped)；去 DEMO 徽章；phone 脱敏 3\*\*\*\*4 正则；会员 banner 新文案；统计栏 4 列等宽；profile-foot 文字裸 div → RouterLink 卡片；移动端媒体查询 1 列；加 padding-bottom:80px 防 BottomNav 叠 |
| A2-2 | apps/user-h5/src/views/AssistantView.vue | SFC 标准三层重排 style→顶层 scoped；超长单行 CSS 拆多块；chat-top sticky z-20；chat-input position:fixed 宽度 min(100%,760px) bottom 0+ safe-area；typing dots bounce keyframes 完整；chat-msg--user grid 右对齐；BottomNav 单实例；actions/facts/community 分隔条规范 |
| A2-3 | apps/user-h5/src/router.ts | requiresAuth 改 composable：引入 bootstrap/setRouter/useResidentAuth；beforeEach 先 await bootstrap() 等待三态从 unknown→authenticated/guest；requiresAuth 且非 authenticated 跳 /resident/login?redirect=；publicPage 且 authenticated 跳首页/redirect |
| A2-4 | apps/user-h5/src/api.ts | ① 引入 onUnauthorized；② axios response 401 拦截器针对 resident/me、reminders、items/*/reminder 清 localStorage 跳 login；③ residentHeaders 定义从 L334 前移至 L26；④ setFavorite/createReminder/fetchReminders/deleteReminder/recordUsageEvent 统一补 `headers: residentHeaders()`；⑤ 删除末行重复 residentHeaders 定义 |
| A2-5 | services/backend/src/main/java/cn/jianda/publicapi/PublicController.java | ① 新增 imports: StandardCharsets/MessageDigest/NoSuchAlgorithmException/HexFormat；② 新增 resolveResidentUserId(token): Long（sha256 token_hash + resident_session JOIN resident_user 查 ACTIVE + 未过期）；③ 新增 sha256(value) helper；④ 三个接口 reminders GET / createReminder POST / deleteReminder DELETE 新增 @RequestHeader X-Resident-Token (required=false)；居民模式 effectiveUser="RESIDENT-<id>" 保 anonymous_user_id NOT NULL 唯一约束 + resident_user_id FK 同步回填；游客模式保留 anonymous_user |

### A.3 文档文件（上轮 Phase 9.9.4 的验收产物，本轮纳入 Git）
| # | 相对路径 | 内容 |
|---|---------|------|
| A3-1 | docs/PHASE9_9_4_CHECKLIST.md | Phase 9.9.4 8 Gate 验收清单 (8/8 PASS) |
| A3-2 | docs/PHASE9_9_4_ISSUES_FOUND.md | Phase 9.9.4 已知遗留 2 Minor Issue 列表：① reminder FK 缺值 500 ② Profile/Assistant SFC style 嵌 template 内 → 本轮均已修复 |
| A3-3 | docs/PHASE9_9_4_REAL_ACCEPTANCE.md | Phase 9.9.4 真实环境 DeepSeek 接入 / 428003 上海宝山政务入库 / hot_score 5 维 SQL / community 邻里发帖 的验收实录 |
| A3-4 | docs/PHASE9_9_4_UI_REPORT.md | Phase 9.9.4 商业化 UI：DeepTeal #0E5A55 墨绿 + WarmIvory rgb(247,244,238) 暖色背景系统 10 屏截图索引 |

---

## §B 组员需自配的环境变量（复制 .env.example → .env，按下列项填）

> 分类说明：**[OPTIONAL]** 本地 demo 可留空按 mock/local_test 默认；生产必须填。
> 获取方式：各平台控制台 → API Key 管理 → 新建项目复制。

| 变量名 | 分类 | 是否必填 | 获取方式 / 说明 |
|-------|------|---------|----------------|
| MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD / DB_PASSWORD | 数据库 | ✅ 开发必填 | 本地随机 16+ 位，或用 example 默认 dev 值即可 |
| JWT_SECRET | 管理员鉴权 | ✅ 生产必填 | openssl rand -hex 32；管理员 SaaS JWT 签名密钥（居民用 X-Resident-Token sha256 独立） |
| LLM_PROVIDER=external | AI 总开关 | 生产必切 | 生产必须切 external；开发默认=mock 即可跑通冒烟 |
| EXTERNAL_LLM_API_KEY | AI-DeepSeek | LLM_PROVIDER=external 时必填 | https://platform.deepseek.com → API Keys → 新建；36 位 key |
| EXTERNAL_LLM_BASE_URL | AI-DeepSeek | OPTIONAL | 默认 https://api.deepseek.com |
| EXTERNAL_LLM_MODEL | AI-DeepSeek | OPTIONAL | 默认 deepseek-v4-flash |
| WEB_SEARCH_PROVIDER=tavily | AI-联网搜索 | 生产推荐填 | 缺省 disabled；Phase9.9.4 验收可留默认 |
| WEB_SEARCH_API_KEY | AI-Tavily | provider=tavily 时必填 | https://tavily.com 控制台申请 |
| PAYMENT_PROVIDER / PAYMENT_LOCAL_TEST_ENABLED | 商业化支付 | OPTIONAL | 开发=local_test + true 即可全流程 demo；生产必须接真实商户号 |
| VITE_AMAP_KEY + AMAP_SECURITY_JS_CODE | 地图服务 | H5 老人端地图必填 | https://console.amap.com → 应用管理 → JS API 2.0 Key；安全密钥别提交到 Git |
| VITE_API_BASE_URL / VITE_PROXY_TARGET | H5 dev API 代理 | OPTIONAL | 本地 Vite dev 默认空；生产部署填 Nginx 网关地址 |
| VITE_H5_BASE_URL | H5 / institution 跳转 | OPTIONAL | 生产填 SaaS → H5 的公共域名 |
| JIANDA_CORS_ALLOWED_ORIGINS | 安全/CORS | 生产必改 | 逗号分隔真实前端域名；**生产绝对禁用 \*** |
| CRAWL_SCHEDULER_ENABLED + CRAWL_DAILY_TIME | 爬虫调度 | OPTIONAL | 生产开；开发关 |
| SPRING_PROFILES_ACTIVE=prod | Spring 生产 | 生产必加 | 启动 backend container 或 systemd 时 inject 此 env |

> **额外声明**：本仓库 .env 永远被 .gitignore 忽略，不会入 Git。.env.example 仅为变量名清单。

---

## §C 本机运行产物 / 可重建缓存（**勿入 Git**，清单供组员自行判断重建）

| 目录 / 文件 | 大小级别 | 重建方式 |
|------------|---------|---------|
| artifacts/phase9-9-4-final/ | ~16 截图 + 2 JSON | 重跑 §7 Gate → Playwright 生成或浏览器验收时截图；非必需 |
| apps/institution-web/dist/ + apps/user-h5/dist/ | ~50MB×2 | `npm run build` 对应子项目；或 docker compose build frontend 自动构建 |
| services/backend/target/ | ~150MB | Maven compile / `mvnw -pl services/backend -am package`；或 docker compose build backend |
| services/ai-service/\_\_pycache\_\_/ + .pytest_cache/ | <10MB | pytest tests/ 自动生成；重建= `pytest tests/ -x` |
| node_modules/ (root + apps/*) | ~1GB×N | `npm install` / `npm ci` |
| .mvn/wrapper/*.jar + Maven ~/.m2 本地仓 | 大 | 首次 Maven wrapper 下载；CI 有缓存则跳过 |
| Docker volumes（mysql_data / uploads） | 大 | 首次 docker compose up -d mysql 自动建 + Flyway V1→V41 migrate；清空重建见 §10 fresh clone |
| .env（根目录） | <2KB | 从 §B 清单 + .env.example 复制填写 |
| .claude/* / .vscode/* / .idea/* | 小 | 本机编辑器 worktree；各人生成 |

---

## §D .gitignore 误伤审计（2026-08-25 执行 `git check-ignore -v` 结果）

| 被检路径 | 结果 |
|---------|-----|
| services/backend/.../V41__resident_reminder_resident_user_column.sql | NOT ignored ✅ |
| apps/user-h5/src/composables/useResidentAuth.ts | NOT ignored ✅ |
| docs/PHASE9_9_4_CHECKLIST.md | NOT ignored ✅ |
| apps/user-h5/src/views/ProfileView.vue (tracked 源码抽样) | NOT ignored ✅ |
| scripts/gov_import.py (爬虫脚本抽样) | NOT ignored ✅ |

结论：.gitignore 规则干净，不会误伤源码/migration/docs/scripts 入 git。

---

## §E 快速启动步骤（供新组员按顺序执行，配合 §B 变量）

```powershell
# E1. Clone
git clone https://github.com/LTY0527/JianDa.git JianDa
cd JianDa

# E2. Git 分支（要最新收口就切 main）
git switch main
git pull --ff-only origin main

# E3. 环境变量
Copy-Item .env.example .env
# 按 §B 至少填：JWT_SECRET；要 AI 助手就填 EXTERNAL_LLM_API_KEY + 切 LLM_PROVIDER=external

# E4. 构建（首次 10~20 分钟，依赖网络）
docker compose build --no-cache mysql backend frontend ai-service

# E5. 启动（mysql 先 healthy 1 分钟，Flyway 自动 V1→V41）
docker compose up -d
docker compose ps
# 期望 4 containers = mysql / ai-service / backend / frontend 全部 healthy

# E6. H5 老人端验收 → 浏览器打开 http://127.0.0.1
#   - 无 token → 自动跳 /resident/login ✅ (Gate C CaseA)
#   - 登 demo_chen / Resident@123 → 进首页 ✅ (CaseB)
#   - Profile: 会员banner/Hero/4col统计/卡片footer/BottomNav唯一 ✅ (Gate A)
#   - Assistant: 空+3问/引用/气泡/sticky输入框 ✅ (Gate B)

# E7. SaaS 管理端验收 → http://127.0.0.1:8090 → platform_admin / Jianda@123
#   - Documents 11 列（材料/来源/地区/栏目/Stage/ETA/机构/状态/进度/更新/操作）✅
```

---

## §F 交接签名

> 交付人：Phase 9.9.4_1 自动生成
> 交付时间：2026-08-25
> 下阶段入口：docs/PHASE9_9_4_1_FINAL_REPORT.md（§9 fresh clone + §11 Gate A-F PASS 表）
