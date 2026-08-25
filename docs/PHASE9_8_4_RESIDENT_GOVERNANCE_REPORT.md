# Phase 9.8_4 居民间互动治理与 REPORTED 阻断报告

日期：2026-08-24
分支：`feat/phase9-8-core-reliability-v1`

## Gate

`RESIDENT_REAL_ACCEPTANCE: PASS`（9.8_3 为 PARTIAL）

## P0-4 尾巴修复

### A. REPORTED 帖子仍可被点赞/评论

远端旧代码：

```java
private void visiblePost(long id) {
    ... status IN ('VISIBLE','REPORTED')
}
```

公开 Feed 虽隐藏 REPORTED，但知道 post id 时点赞 / 评论 / 读评论仍能通过。

**修复**：`services/backend/src/main/java/cn/jianda/publicapi/ResidentCommunityController.java`

- 公开点赞 / 评论 / 读评论改用 `requirePublicVisiblePost(id)`，查询 `SELECT COUNT(*) FROM community_post WHERE id=? AND status='VISIBLE'`；
- 仅 `VISIBLE` 可公开交互，`REPORTED` / `HIDDEN` 一律拒绝；
- 治理接口 `requireModeratablePost` 仍可访问 REPORTED/HIDDEN。

### B. 公开媒体访问

`services/backend/src/main/java/cn/jianda/publicapi/CommunityMediaService.java`

```java
"SELECT m.storage_path,m.thumbnail_path,m.mime_type FROM community_post_media m
 JOIN community_post p ON p.id=m.community_post_id WHERE m.id=? AND p.status='VISIBLE'"
```

REPORTED/HIDDEN 帖子图片不再能通过公开 media API 直接访问。原则：公开 Feed 不可见 → 公开交互与公开媒体也不应暴露；管理员治理接口仍可访问必要信息。

## 真实回归（两个真实居民账号 + 平台管理员）

脚本：`scripts/test_reported_post_blocking.ps1`
账号：`demo_chen`（A 发帖）/ `demo_li`（B 互动）/ `platform_admin`（治理）
帖子 ID：13
机器可读：`artifacts/phase9-8-4-final/resident-governance-real.json`

### 13 步全链路

| 步骤 | 结果 |
| --- | --- |
| feed_visible_after_create | PASS |
| like_before_report | PASS |
| comment_before_report | PASS |
| report_post | PASS |
| feed_hidden_after_report | PASS |
| like_after_report_blocked | PASS_BLOCKED(404) |
| comment_after_report_blocked | PASS_BLOCKED(404) |
| admin_queue_has_reported | PASS |
| admin_restore_visible | PASS |
| like_after_restore | PASS |
| admin_hide | PASS |
| like_after_hidden_blocked | PASS_BLOCKED(404) |
| comment_after_hidden_blocked | PASS_BLOCKED(404) |

`gate: PASS`。全真实 API，无 Mock / fixture / page.route。

## 数据库终态（2026-08-24 14:25）

community_post：13 条（VISIBLE 9 / REPORTED 0 / HIDDEN 4）。治理回归用帖 ID 13 终态 HIDDEN（预期，与脚本最后 admin_hide 一致）。

## 结论

REPORTED/HIDDEN 帖子公开互动与公开媒体均已阻断；管理员恢复/隐藏治理闭环正确；两真实居民账号互动作与治理动作均经真实 API 产生。Resident Gate 关闭。
