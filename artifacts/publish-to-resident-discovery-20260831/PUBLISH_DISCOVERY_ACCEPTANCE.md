# 机构发布到居民发现链路真实验收

日期：2026-08-31
验收方式：Docker 真实 MySQL 数据、真实 Spring Boot/H5 页面、Playwright Chromium，无 API Mock。

## 被追踪文章

| 字段 | 值 |
| --- | --- |
| document_id | 127 |
| published_item_id | 87 |
| slug | `guide-127` |
| 标题 | `upload` |
| 发布地区 | 宝山区 · 顾村镇 |
| regionCode | `310113109` |
| local_scope | `LOCAL_TOWN` |
| published_at | `2026-08-31 01:57:56` |
| expires_at | `NULL` |
| DB 状态 | `PUBLISHED` |

最新异常记录 `news-125` 保留原现场，其 `UNSPECIFIED/NULL` 地区不会被修改为验收数据；修复后带居民地区访问返回 404，新的未分类发布会在发布事务中被拒绝。

## 真实验收矩阵

| Gate | 结果 | 证据 |
| --- | --- | --- |
| 机构端已发布记录 | PASS | 已发布列表存在 `upload`，状态为已发布 |
| 顾村列表 | PASS | HTTP 200，JSON 包含 `guide-127` |
| 顾村标题搜索 | PASS | HTTP 200，`keyword=upload` 命中 |
| 顾村正文搜索 | PASS | HTTP 200，`keyword=青禾一村广场` 命中 |
| 顾村详情 | PASS | HTTP 200，`application/json;charset=UTF-8` |
| 大场列表隔离 | PASS | HTTP 200，结果不包含 `guide-127` |
| 大场搜索隔离 | PASS | HTTP 200，结果不包含 `guide-127` |
| 大场详情隔离 | PASS | HTTP 404 |
| 未分类详情隔离 | PASS | `news-125?regionCode=310113109` 为 HTTP 404 |
| H5 首页 | PASS | 顾村首页推荐流展示 `upload` |
| H5 标题搜索 | PASS | 无刷新技巧，页面直接命中 |
| H5 正文搜索 | PASS | 真实 `/api/public/search` 请求命中 |
| 搜索进入详情 | PASS | 进入 `/news/guide-127` 并展示同一标题 |
| 切回页面刷新 | PASS | focus 后重新请求 `/api/public/items` |
| 移动端横向溢出 | PASS | 390×844 下 `scrollWidth <= innerWidth` |
| 浏览器控制台 | PASS | 目标链路无 console error |

## 接口响应

| 地址 | HTTP | Content-Type | 目标记录 |
| --- | --- | --- | --- |
| `/api/public/items?regionCode=310113109` | 200 | `application/json;charset=UTF-8` | FOUND |
| `/api/public/search?keyword=upload&regionCode=310113109` | 200 | `application/json;charset=UTF-8` | FOUND |
| `/api/public/search?keyword=青禾一村广场&regionCode=310113109` | 200 | `application/json;charset=UTF-8` | FOUND |
| `/api/public/items/guide-127?regionCode=310113109` | 200 | `application/json;charset=UTF-8` | FOUND |
| `/api/public/items?regionCode=310113102` | 200 | `application/json;charset=UTF-8` | NOT_FOUND |
| `/api/public/search?keyword=upload&regionCode=310113102` | 200 | `application/json;charset=UTF-8` | NOT_FOUND |
| `/api/public/items/guide-127?regionCode=310113102` | 404 | `application/json;charset=UTF-8` | NOT_FOUND |

## 实现结论

- 发布：拒绝未分类范围，规范并校验四类公开范围，不允许通过取消地区过滤“修复”。
- 详情：检查过期；居民 H5 请求统一带当前地区并应用 `PublishedRegionScope`。
- 搜索：H5 改为真实后端搜索；后端覆盖居民看到的已发布正文和结构化字段，一篇只返回一次。
- 首页：72 小时新内容 feed 保底，不改变 Hero 的既有重要性和真实图片规则。
- 刷新：首页和资讯页在 focus/visibility 恢复后按 5 秒阈值刷新，无高频轮询和重复并发。
- 机构端：发布前显示地区缺失阻止信息；发布成功卡显示发布地区和栏目；居民端链接携带可支持的街镇地区 query。

## 自动测试

| 命令 | 结果 |
| --- | --- |
| `mvn -f services/backend/pom.xml test` | PASS，117 tests，0 failures，0 errors，0 skipped |
| `npm run typecheck --workspace apps/user-h5` | PASS |
| `npm run build --workspace apps/user-h5` | PASS，Vite 1767 modules transformed |
| `npm run typecheck --workspace apps/institution-web` | PASS |
| `npm run build --workspace apps/institution-web` | PASS，Vite 1703 modules transformed |
| `docker compose build backend frontend` | PASS |
| `npx playwright test tests/e2e/real/publish-resident-discovery.spec.ts` | PASS，1/1，10.9s |

Docker 运行态：MySQL、AI Service、Backend、Frontend 四个容器均为 healthy；`8001/health`、`8080/actuator/health`、`8090/health`、`80/health` 均为 HTTP 200。

## 截图

1. `01_institution_publish_success.png`：机构端已发布记录。
2. `02_public_items_contains_new_post.png`：顾村公开 items JSON 包含目标 slug。
3. `03_home_new_post_visible_390x844.png`：顾村首页推荐流展示新发布内容。
4. `04_search_unique_keyword_found.png`：标题搜索命中。
5. `05_search_body_keyword_found.png`：正文关键词搜索命中。
6. `06_new_post_detail.png`：同一 slug 的居民详情。
7. `07_wrong_region_not_visible.png`：切换大场镇后无结果且不泄漏。

## 剩余风险

- 历史 `news-125` 仍保留未分类现场，不会出现在居民地区列表；需要管理员通过业务接口完成地区分类后重新发布，不能直接改库。
- 本轮真实浏览器覆盖 Chromium 390×844；未执行 Safari/WebKit 真机回归。
- 测试记录标题 `upload` 可读性较弱，但没有为通过验收篡改既有真实业务数据。
