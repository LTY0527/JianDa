import { expect, test, type Page, type Route } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const artifactDir = path.resolve("artifacts/phase9-9-2-final");
fs.mkdirSync(artifactDir, { recursive: true });
const artifact = (name: string) => path.join(artifactDir, name);

async function json(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? "成功" : "失败", data }),
  });
}

async function platformSession(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "phase992-platform-token");
    localStorage.setItem("jianda_user_info", JSON.stringify({
      id: 1, organizationId: 1, username: "platform_admin", displayName: "平台管理员",
      role: "PLATFORM_ADMIN", organizationName: "简达平台",
    }));
  });
}

test("未配置高德凭据时明确降级且不再渲染假地图", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url);
  await page.screenshot({ path: artifact("h5-home-dachang-390.png"), fullPage: true });
  await page.getByRole("button", { name: "选择所在地区" }).click();
  await expect(page.getByText("地图服务尚未配置")).toBeVisible();
  await expect(page.locator(".region-map-panel svg")).toHaveCount(0);
  await page.screenshot({ path: artifact("h5-amap-unconfigured-390.png"), fullPage: false });
  await page.getByRole("button", { name: /顾村镇/ }).click();
  await expect(page.getByRole("button", { name: "选择所在地区" })).toContainText("顾村镇");
  await page.screenshot({ path: artifact("h5-home-gucun-390.png"), fullPage: true });
  await page.evaluate(() => localStorage.setItem("jianda_region", JSON.stringify({
    province: "上海市", city: "上海市", district: "宝山区", street_or_town: "庙行镇", region_code: "310113112",
  })));
  await page.reload();
  await expect(page.getByRole("button", { name: "选择所在地区" })).toContainText("庙行镇");
  await page.screenshot({ path: artifact("h5-home-miaohang-390.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});

test("异步批量加入展示进度并在刷新后可恢复任务", async ({ page }) => {
  await platformSession(page);
  let importPolls = 0;
  let batchBody: unknown;
  const source = {
    id: 31, domain: "official.example.gov.cn", source_name: "居民健康来源", source_type: "GOVERNMENT",
    authority_level: "A", enabled: true, crawl_mode: "MANUAL", discovery_mode: "SECTION",
    homepage_url: "https://official.example.gov.cn/", section_url: "https://official.example.gov.cn/list",
    daily_crawl_time: "03:30", max_articles_per_run: 5, allow_image_cache: false,
    allow_image_candidates: true, allow_auto_crawl: false, allow_auto_ai: false,
    daily_article_budget: 0, daily_token_budget: 0, requires_manual_review: true,
    schedule_mode: "DAILY", interval_hours: 24, schedule_timezone: "Asia/Shanghai", recent_days: 7,
    include_keywords: "健康", exclude_keywords: "采购", auto_save_draft: true,
    duplicate_strategy: "SKIP", max_retries: 3, image_usage_policy: "MANUAL_REVIEW",
    image_usage_basis: "", auto_approve_images: false, image_cache_allowed: false,
  };
  const candidate = {
    canonical_url: "https://official.example.gov.cn/a", discovered_url: "https://official.example.gov.cn/a",
    title: "老年健康社区服务", discovery_method: "SECTION", dedup_key: "a", imported: false,
    relevance_level: "HIGH", relevance_score: 99, recommended_topic: "健康",
    recommendation_reason: "与居民生活和适老公共服务高度相关", region_name: "宝山区",
  };
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/source-registries") return json(route, [source]);
    if (path === "/api/source-registries/discover-jobs/801") return json(route, {
      id: 801, status: "SUCCESS", processing_stage: "COMPLETE", discovered_count: 1,
      added_count: 1, duplicate_count: 0, failed_count: 0,
      discoveryResult: { candidates: [candidate], filtered_external_count: 0, filtered_navigation_count: 0 },
    });
    if (path === "/api/source-registries/31/collect-batch") {
      batchBody = request.postDataJSON();
      return json(route, { jobId: 901, status: "PENDING", total: 1 });
    }
    if (path === "/api/source-registries/import-jobs/901") {
      importPolls += 1;
      if (importPolls === 1) return json(route, {
        id: 901, status: "RUNNING", discovered_count: 1, added_count: 0, duplicate_count: 0,
        failed_count: 0, progress_message: "正在抓取第 1/1 篇",
      });
      return json(route, {
        id: 901, status: "SUCCESS", discovered_count: 1, added_count: 1, duplicate_count: 0,
        failed_count: 0, result: { imported: [{ documentId: 88 }] },
      });
    }
    return json(route, []);
  });

  await page.goto(`${institutionUrl}/public-sources/31/check/801`);
  await page.getByRole("button", { name: "全选推荐内容" }).click();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "加入所选内容（1）" }).click();
  expect(batchBody).toEqual({ urls: ["https://official.example.gov.cn/a"] });
  await expect(page.getByText("任务 #901 · 刷新或离开页面不会中断。")).toBeVisible();
  await page.screenshot({ path: artifact("admin-import-progress-1440.png"), fullPage: true });
  await expect(page.getByText(/已加入 1 篇，重复 0 篇，失败 0 篇/)).toBeVisible({ timeout: 5_000 });
  await expect.poll(() => page.evaluate(() => localStorage.getItem("jianda_import_job_31"))).toBeNull();
});

test("会员周月年套餐支持支付宝和微信正式支付会话 UI", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("jianda_resident_token", "phase992-resident-token"));
  await page.route("**/api/public/resident/me", (route) => json(route, {
    id: 1, username: "demo_chen", nickname: "陈阿姨", district: "宝山区",
    streetOrTown: "大场镇", regionCode: "310113102", demo: true,
  }));
  const methods: string[] = [];
  let sessionIndex = 0;
  await page.route("**/api/public/membership/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/plans")) return json(route, [
      { id: 1, name: "周卡", billing_period: "WEEK", price_cents: 290, benefits: ["阅读偏好同步"], demo_price: true },
      { id: 2, name: "月卡", billing_period: "MONTH", price_cents: 790, benefits: ["阅读偏好同步"], demo_price: true },
      { id: 3, name: "年卡", billing_period: "YEAR", price_cents: 5900, original_price_cents: 9480, benefits: ["隐藏合作推广位"], demo_price: true },
    ]);
    if (path.endsWith("/capabilities")) return json(route, { available: true, provider: "LOCAL_TEST", testEnvironment: true, realPaymentAvailable: false, message: "测试环境支付链路已就绪" });
    if (path.endsWith("/me")) return json(route, { active: sessionIndex > 1, plan_name: "月卡", expires_at: "2026-09-25T00:00:00+08:00" });
    if (path.endsWith("/payments") && request.method() === "POST") {
      const body = request.postDataJSON() as { method: string };
      methods.push(body.method);
      sessionIndex += 1;
      return json(route, { sessionId: `payment-${sessionIndex}`, method: body.method, amountCents: 790, planName: "月卡",
        provider: "LOCAL_TEST", qrPayload: `jianda-local-payment://session/payment-${sessionIndex}`, status: "PENDING", expiresAt: "2026-08-25T13:00:00+08:00", testEnvironment: true });
    }
    if (path.endsWith("/cancel") && request.method() === "POST") return json(route, {
      sessionId: "payment-1", method: "ALIPAY", amountCents: 790, planName: "月卡", provider: "LOCAL_TEST",
      qrPayload: "jianda-local-payment://session/payment-1", status: "CANCELLED", expiresAt: "2026-08-25T13:00:00+08:00",
    });
    if (/\/payments\/payment-\d+$/.test(path) && request.method() === "GET") return json(route, {
      sessionId: `payment-${sessionIndex}`, method: methods.at(-1), amountCents: 790, planName: "月卡",
      provider: "LOCAL_TEST", qrPayload: `jianda-local-payment://session/payment-${sessionIndex}`,
      status: sessionIndex === 2 ? "SUCCESS" : "PENDING", expiresAt: "2026-08-25T13:00:00+08:00", testEnvironment: true,
    });
    return json(route, null);
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/membership`);
  await expect(page.getByRole("heading", { name: "简达安心会员" })).toBeVisible();
  await expect(page.getByRole("button", { name: "选择周卡" })).toBeVisible();
  await expect(page.getByRole("button", { name: "选择年卡" })).toBeVisible();
  await page.screenshot({ path: artifact("h5-membership-390.png"), fullPage: true });
  await page.getByRole("button", { name: "选择月卡" }).click();
  await page.screenshot({ path: artifact("h5-membership-payment-sheet-390.png"), fullPage: false });
  await page.getByRole("button", { name: "确认支付" }).click();
  await expect(page.getByText("支付宝", { exact: true })).toBeVisible();
  expect(await page.locator("canvas").evaluate((canvas: HTMLCanvasElement) => canvas.toDataURL().length)).toBeGreaterThan(100);
  await page.screenshot({ path: artifact("h5-alipay-payment-qr-390.png"), fullPage: false });
  await page.getByRole("button", { name: "取消支付" }).click();
  await page.getByRole("button", { name: "完成" }).click();
  await page.getByRole("button", { name: "选择月卡" }).click();
  await page.getByRole("button", { name: "微信支付" }).click();
  await page.getByRole("button", { name: "确认支付" }).click();
  await expect(page.getByText("微信支付", { exact: true })).toBeVisible();
  await page.screenshot({ path: artifact("h5-wechat-payment-qr-390.png"), fullPage: false });
  await expect(page.getByText(/支付成功，会员权益已生效/)).toBeVisible({ timeout: 5_000 });
  expect(methods).toEqual(["ALIPAY", "WECHAT"]);
  await expect(page.getByText(/演示支付|模拟已扫码|DEMO/)).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});

test("商业运营看板只展示真实统计和可用说明", async ({ page }) => {
  await platformSession(page);
  await page.route("**/api/commercial/overview", (route) => json(route, {
    plans: 1, activeSubscriptions: 1, membershipPlans: 3, activeMembers: 2, newMembersThisMonth: 2,
    verifiedProviders: 0, activeProducts: 0, ordersThisMonth: 0, pendingRefunds: 0, activeSponsors: 0,
    payment: { available: false, provider: "NONE", message: "线上支付暂未开通" },
  }));
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto(`${institutionUrl}/commercial`);
  await expect(page.getByRole("heading", { name: "商业运营" })).toBeVisible();
  await expect(page.getByText("本月新增会员")).toBeVisible();
  await expect(page.getByText("REAL_PAYMENT_PROVIDER_ACCEPTANCE")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "添加第一个服务商" })).toHaveCount(0);
  await page.screenshot({ path: artifact("admin-commercial-dashboard-1440.png"), fullPage: true });
});
