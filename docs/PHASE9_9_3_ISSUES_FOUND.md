# Phase 9.9.3 本次 TRAE 接管发现的问题与处理

> 接管日期：2026-08-25
> 原则：只修回归点，不重写整个模块

## 1. frontend 容器运行旧镜像，缺少 P0-A 栏目调整 UI

**现象**：机构端"已发布内容"页面表头为 `标题 机构 发布日期 状态 操作`，缺少"栏目"列与"调整栏目"按钮，但源码 `PublishedView.vue` 已含栏目列与调整入口。

**根因**：P0-A 后端 API 与前端代码已在 `5992e0a feat(机构): 支持已发布内容受控调整首页栏目` 提交，但 Docker frontend 容器未重建，仍运行旧镜像（dist 不含 `调整栏目` / `channel-picker`）。

**验证**：
```
docker exec jianda-frontend-1 sh -c "grep -l '调整栏目' /usr/share/nginx/institution/assets/*.js"
重建前：NOT_FOUND
重建后：FOUND（PublishedView-BQs4tz7c.js）
```

**修复**：`docker compose build frontend && docker compose up -d frontend`，不触碰 mysql/backend/ai-service。

**验收**：重建后机构端 spec 4/4 PASS，"已发布内容"页表头含"栏目"列，"调整栏目"按钮点击后展示 7 栏目 picker（健康/养老/助餐/办事/防诈/活动/社区）。

## 2. H5 首页公开内容列表 regionCode=310113 返回 403

**现象**：性能测试 `GET /api/public/items?regionCode=310113`（宝山区码）返回 403，但 `regionCode=310113102`（大场镇码）返回 200。

**根因**：`PublicController.items` 的 `PublishedRegionScope.predicate` 对区级码 `310113` 拒绝（系统要求具体到街镇级 regionCode）。H5 首页实际用的是 `activeRegion.value.region_code` = `310113102`（大场镇），所以真实用户不受影响。

**处理**：性能测试改用真实街镇码 `310113102`（与 H5 首页 `HomeView.vue:130` 调用一致），不修改后端逻辑（区级码拒绝属合理设计，避免跨区内容混入）。

**影响范围**：仅性能测试脚本，不影响真实用户。`service-directory` 接口对区级码宽松（200），因其 region 处理逻辑不同。

## 3. 性能测试 fetch 自定义 header 触发 403 误判

**现象**：首次性能测试用 `page.evaluate` + `fetch` 绝对 URL 带 `X-Anonymous-User`/`X-Visitor-Id` header 仍 403。

**根因**：与问题 2 同源——实际是 regionCode 值问题，header 不是根因（service-directory 同样带 header 却 200）。

**处理**：通过 `page.on('request')` 抓取 H5 首页加载时 axios 真实请求的 header 与 URL 对比，定位到 regionCode 差异。

## 4. 支付会话性能测试请求体字段名错误

**现象**：`POST /api/public/membership/payments` 返回 500，后端日志 `NullPointerException: Cannot invoke "java.lang.Long.longValue()" because the return value of "MembershipController$PaymentRequest.planId()" is null`。

**根因**：性能测试请求体用了 `{plan:"ANNUAL", provider:"alipay"}`，但 `PaymentRequest` record 是 `(Long planId, String method)`，字段名错误导致 planId 反序列化为 null。

**处理**：先 `GET /api/public/membership/plans` 取真实 planId，再 `POST` 用 `{planId: <id>, method: "ALIPAY"}`，与前端 `createPaymentSession` 调用一致。

**验收**：支付会话创建 p95=26ms status=200，sessionId 真实生成。

## 5. 机构端"上传材料"页 heading 断言错误

**现象**：断言 `heading "上传材料"` 失败，实际 h1 是"新增材料"。

**处理**：修正断言为"新增材料"，并补充 tab（上传 PDF 或图片 / 导入网页文章）与按钮（上传并开始处理）断言。

## 6. Assistant 30 问 RAG 评估逻辑错误（已修复）

**现象**：旧评估依赖 `completion_tokens > 0`，但 assistant API 响应无此字段，导致 RAG 误判 0/10。

**根因**：`assistant_30q.py` 评估逻辑依赖不存在的 `completion_tokens` 字段。

**处理**：`reeval_rag.py` 改用 `answer_len > 50` + (`has_citation_marks` 或 `citations_count > 0` 或 `mode == "rag"`) 作为 RAG 通过标准。重新评估后 RAG 10/10 PASS。

## 7. Assistant 30 问联网部分 0/10（待 Tavily 凭据）

**现象**：`WEB_SEARCH_PROVIDER=disabled`，联网搜索 provider 未配置，10 个联网低风险问题全部 SKIPPED。

**根因**：`.env` 无 `WEB_SEARCH_API_KEY`，需注册 Tavily 获取免费开发 Key。

**代码状态**：`ConfiguredWebSearchProvider` 已实现 Tavily 调用路径（`POST https://api.tavily.com/search` + `Authorization: Bearer <key>`），只需配置凭据并重启 backend 即可生效，无需写新代码。

**处理**：按提示词要求，其余全部 PASS 后输出 ACTION_REQUIRED，等待用户在 Tavily 官方控制台完成注册/验证后回复"继续"。

## 8. PowerShell 中文编码与请求体编码（历史，本次未复现）

前序会话已修复：脚本注释改 ASCII、请求体用 UTF8.GetBytes。本次新写的 Playwright spec 用 TypeScript，无此问题。
