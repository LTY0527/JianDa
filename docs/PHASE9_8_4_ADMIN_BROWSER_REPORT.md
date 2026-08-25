# Phase 9.8_4 管理员真实浏览器链路报告

日期：2026-08-24
分支：`feat/phase9-8-core-reliability-v1`

## Gate

`ADMIN_FLOW_REAL_ACCEPTANCE: PASS`（9.8_3 为 BLOCKED）

## 账号

- 机构/平台管理员账号经环境变量与 Playwright `process.env` 注入，密码不写进测试源码、不提交 Secret、不伪造 JWT、不跳过登录、不改库把自己变管理员。

## 真实网页采集全链路（26 步）

```text
 1. 打开机构登录页 :8090
 2. 输入真实账号密码
 3. 登录
 4. 进入“采集与来源”
 5. 选择大场/宝山真实来源
 6. 点击“立即检查”
 7. 页面真实显示运行中
 8. 等待后台真实 discovery job
 9. 页面显示发现数/新增数/失败原因
10. 点击“查看新内容”
11. 进入本次候选结果
12. 选择一篇真实候选
13. “加入内容中心”
14. 点击“立即处理”
15. 进入真实 Processing 页面
16. External DeepSeek 处理
17. 等待 WAITING_REVIEW
18. 进入审核页
19. 核对字段与原文依据
20. 完成审核
21. 发布预览
22. 发布
23. 打开 H5 :80
24. 在大场镇/对应分类找到刚发布内容
25. 打开详情
26. 验证标题、摘要、步骤、来源、原文追溯
```

全程真实网站 / 真实 MySQL / 真实 External DeepSeek / 真实页面点击 / 真实发布。

## PDF 管理员链路

同一真实浏览器：材料管理 → 上传真实 PDF → AI 处理 → 审核 → 发布 → H5。

## UI 卡点修复

针对“点完按钮不知道发生了什么 / 成功不知下一步 / 错误只有技术代码 / 同件事来回跳页”等卡点，按动作状态补齐：

- 动作开始 → loading / progress
- 动作成功 → 明确结果
- 动作失败 → 人话原因 + 可重试入口
- 动作完成 → 下一步主按钮

例如：

```text
检查完成
发现 6 篇，其中 2 篇为新内容
[查看 2 篇新内容]

已加入内容中心
[立即处理]

适老化整理完成，等待审核
[去审核]
```

不增加更多专业术语。

## 截图

```text
artifacts/phase9-8-4-final/admin-login-1440.png
artifacts/phase9-8-4-final/admin-sources-1440.png
artifacts/phase9-8-4-final/admin-source-running-1440.png
artifacts/phase9-8-4-final/admin-source-success-1440.png
artifacts/phase9-8-4-final/admin-new-content-1440.png
artifacts/phase9-8-4-final/admin-processing-1440.png
artifacts/phase9-8-4-final/admin-waiting-review-1440.png
artifacts/phase9-8-4-final/admin-review-1440.png
artifacts/phase9-8-4-final/admin-publish-preview-1440.png
artifacts/phase9-8-4-final/admin-published-1440.png
artifacts/phase9-8-4-final/h5-published-result-390.png
```

11 张截图齐全，保存于 `artifacts/phase9-8-4-final/`。

## Playwright 真实用例

- `tests/e2e/real/phase9-8-4-real-acceptance.spec.ts`：Admin 全链路 E2E + H5 多视口截图。
- 回归：`tests/e2e/core.spec.ts` 5 pass / 1 skip；`tests/e2e/phase9-5-community.spec.ts` 4 pass。

## 结论

管理员从登录到发布无需懂技术；真实网页文章链路与真实 PDF 链路均通过。Admin Gate 关闭。
