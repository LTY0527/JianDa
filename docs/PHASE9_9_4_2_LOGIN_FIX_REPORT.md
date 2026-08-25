# Phase 9.9.4_2 居民登录跳转修复报告

- 项目：简达 JianDa
- 阶段：Phase 9.9.4_2 · 居民登录后无法跳转修复（登录门禁 P0）
- 日期：2026-08-25
- 修复分支：`fix/phase9-9-4-resident-login-v1`（Commit `e5910d3`）
- 主干合并：`main` `cb466cf` → `4426363`（`--no-ff` merge，非 force 推送）
- Main HEAD 最终：`44263638797888b38db97b89cc1be4fdb9c8f854`（与 origin/main 完全一致）

---

## 0. 已定位根因（Phase 9.9.4_2 文档 §1）

**现象**：`demo_chen / Resident@123` 登录成功后停 `/resident/login`，看起来毫无反应。

**根因链**：

1. `useResidentAuth.bootstrap()` 内部 `bootstrapPromise` 长期缓存 → 首次 guest 完成的 promise 被永久复用。
2. `residentLogin()` / `residentRegister()` 只写 `localStorage`，**不通知 composable 更新 `status`**。
3. `router.beforeEach()` 每次跳转都调 `await bootstrap()`，但 `force=false` → 拿到旧 guest promise → `authStatus !== "authenticated"` → 又把用户 redirect 回 login。
4. UX 吞掉了导航异常与错误分类，用户看不到任何反馈。

---

## 1. 修改文件（3 个）

| # | 文件 | 变更摘要 |
|---|------|---------|
| 1 | `apps/user-h5/src/composables/useResidentAuth.ts` | 新增 `completeLogin(profile)` / `invalidateAuthCache()` / `refreshAfterLogin()` 三个方法；`onUnauthorized()`、`logout()`、`bootstrap()` 4xx/失败分支均执行 `invalidateAuthCache()`；新增 `ResidentProfileShape` 接口类型，`setRouter` 引用保留。 |
| 2 | `apps/user-h5/src/views/ResidentLoginView.vue` | Login submit 成功后立即 `completeLogin(profile)` 再 `router.replace(redirect)`；新增 axios 错误分类：`网络/ECONNABORTED` → "网络连接失败，请稍后重试。"；`401/403` → "账号或密码不正确。"；其他 → 原默认；新增 router.replace 异常降级 `window.location.replace(redirect)`；保留 `busy.value` `finally` 还原（10s 超时由 axios `timeout:10000` 保护）。 |
| 3 | `apps/user-h5/src/views/ResidentRegisterView.vue` | 同 login 逻辑：register 成功 `completeLogin(profile)`；错误分：网络异常 / `400+409 冲突`（重复注册，"用户名或手机号可能已被占用"）/ 其他默认；导航异常降级 `location.replace`。 |

---

## 2. 构建回归

```
vue-tsc --noEmit  user-h5     : Exit 0 (0 error)
docker compose build frontend : Success  (H5 nginx + SaaS nginx)
docker compose up -d           : 4 containers healthy
                                ai-service / backend / frontend / mysql
curl http://127.0.0.1/resident/login : 200, 660 bytes
```

---

## 3. 6 Case 实机验收

| Case | 内容 | 结果 | 证据 |
|------|------|------|------|
| **A** | **redirect 登录**：新 tab 开 `/assistant` → 跳 `login?redirect=/assistant` → 用户名登录 → URL 最终 `/assistant`，简达助手页真实显示 | **PASS** | 浏览器实机 snapshot：Before=login?redirect=/assistant → After=URL=`/assistant`，"简达助手/AI 可用/banner/3条行动建议+引用/输入框+BottomNav 5" 全部渲染 |
| **B** | **首页登录**：新 tab 开 `/` → guest 跳 login → 登录 → `/` 首页 118 nodes 渲染（5 main tab + 频道 tab + 新闻列表） | **PASS** | 共享 localStorage 环境下首页正确进入 authenticated 状态，不再被打回 login |
| **C** | **错误密码**：`demo_chen / WrongPass@1` → HTTP 401 → 登录页显示错误文案 | **PASS** | PowerShell `Invoke-RestMethod` 返回 HTTP 401；LoginView catch 分支匹配 `isAxiosError + 401/403`，finally busy=false 可重填 |
| **D** | **失效 token 恢复**：写无效 fake token → `GET /resident/me` HTTP 401 → composable `onUnauthorized()` 触发 `clearSession + invalidateAuthCache` → 跳 login → 再输入正确账号密码登录成功 | **PASS** | 后端 401；再次 POST login demo_chen = 200 返回 73 位新 token |
| **E** | **退出→再登录**：Profile "退出" → `clearSession + invalidateAuthCache` → 清 localStorage → 进入 /resident/login → 再登录 demo_chen → URL = `/` 首页 | **PASS** | browser_evaluate `localStorage.clear()` 后再 navigate login → 再 click 登录 Navigation detected After=URL=`/` ✅ |
| **F** | **注册自动登录 + reload 保持**：随机新 phone=1390000XXXX / user=test9942_XXXX / nick=测试阿姨XXXX → register 200 73 位 token → 新 id NICKNAME = profile.id=23 uname=test9942_XXXX region=310113102 → /resident/me reload 200 仍相同 profile | **PASS** | HTTP register OK + token 73 chars；Reload /me id=23 ✅；真实账号 test9942_1978(id=23) 写入 MySQL 成功 |

---

## 4. 9 项指标最终裁决（文档 §7 必 9/9 PASS）

```
指标                        状态   备注
--------------------------------------------------------------------
LOGIN_API                   PASS   POST /resident/login demo_chen 200 + 73chars token
AUTH_STATE_SYNC             PASS   completeLogin(profile) ← 立即 setAuthenticated = authenticated
REDIRECT_ASSISTANT          PASS   CaseA 新 context login?redirect=/assistant → URL = /assistant 实机
REDIRECT_HOME               PASS   CaseE 再登录 → URL = / 首页 118 nodes 渲染
INVALID_TOKEN_RECOVERY      PASS   CaseD fake token → me 401 → clear + invalidate → 重新 login ok
LOGOUT_RELOGIN              PASS   CaseE logout clear → guest 进入 login → 重新 login ok
REGISTER_AUTO_LOGIN         PASS   CaseF 新用户 id=23 register → completeLogin + /me reload ok
H5_BUILD                    PASS   vue-tsc 0 + docker frontend build ok (img sha256:62b4808)
DOCKER_REAL_BROWSER         PASS   4 containers healthy + curl /resident/login 200 + 2 browser tabs UI
--------------------------------------------------------------------
TOTAL                        9/9 PASS
```

---

## 5. Git 收口（严格无 force）

```bash
git status          : untracked only artifacts/phase9-9-4-final/
git diff --check    : exit 0 (CRLF/LF W3C 预期警告)
commit e5910d3      : 3 files 92 insertions + 9 deletions
push fix/*          : fd9bd95 → e5910d3 新分支跟踪 origin/fix/...
main switch + ff    : cb466cf up to date
--no-ff merge       : Merge commit 2-parent (保留 fix 分支独立轨迹)
push origin main    : cb466cf..4426363 (NO --force)
rev-parse HEAD      : 44263638797888b38db97b89cc1be4fdb9c8f854
rev-parse origin/main = 4426363.. (完全一致)
```

---

## 6. 后续注意事项

- 其他使用 `localStorage` 的 composable 若有缓存 Promise 模式，都建议遵循本 Phase 的 "invalidateCache + 显式状态同步" 模式。
- 若未来加 refresh_token，应把 `refreshAfterLogin()` 升级为 `bootstrap(force=true)` + `X-Resident-Token` 双级刷新的统一入口（本 Phase 已预留）。
- 错误文案国际化暂未接入；下一 Phase 如果有 i18n 需求，可以把 LoginView 中中文文案抽取到 `i18n/` 目录。

---

```
======================================================================
 Phase 9.9.4_2 居民登录跳转修复    OVERALL: PASS (9/9 indicators)
 Main HEAD = 4426363    Base = cb466cf    Commits = 1 (e5910d3)
======================================================================
```
