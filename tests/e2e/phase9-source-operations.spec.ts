import { expect, test, type Page, type Route } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

const platformUser = {
  id: 1,
  organizationId: 1,
  username: "platform_admin",
  displayName: "平台管理员",
  role: "PLATFORM_ADMIN",
  organizationName: "简达平台",
};

const registry = {
  id: 31,
  domain: "health.example.gov.cn",
  source_name: "健康权威来源",
  source_type: "GOVERNMENT",
  authority_level: "A",
  enabled: false,
  crawl_mode: "MANUAL",
  discovery_mode: "RSS",
  homepage_url: "https://health.example.gov.cn/",
  rss_url: "https://health.example.gov.cn/rss.xml",
  sitemap_url: "",
  section_url: "",
  daily_crawl_time: "06:30",
  max_articles_per_run: 8,
  allow_image_cache: false,
  allow_image_candidates: false,
  allow_auto_crawl: false,
  allow_auto_ai: false,
  daily_article_budget: 12,
  daily_token_budget: 48000,
  requires_manual_review: true,
  last_status: "NEVER_RUN",
};

const waitingBudget = {
  id: 901,
  source_registry_id: 31,
  source_name: "健康权威来源",
  document_id: 501,
  status: "WAITING_BUDGET",
  reason_code: "DAILY_TOKEN_BUDGET_EXHAUSTED",
  reason_summary: "今日 Token 预算已用完，任务将在预算恢复后继续。",
  estimated_tokens: 6800,
  estimated_recovery_at: "2026-07-30T00:00:00+08:00",
};

async function fulfill(route: Route, data: unknown, status = 200, message = "成功") {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: status === 200 ? 0 : status, message, data }),
  });
}

async function prepare(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem("jianda_token", "isolated-platform-token");
    localStorage.setItem("jianda_user_info", JSON.stringify(user));
  }, platformUser);
}

test("来源默认安全配置、调度预算请求和等待预算状态清晰", async ({ page }) => {
  await prepare(page);
  let savedPayload: Record<string, unknown> | undefined;
  let created = false;
  const enableRequests: boolean[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === "/api/public-sources" && request.method() === "GET") return fulfill(route, []);
    if (path === "/api/source-registries" && request.method() === "GET") {
      return fulfill(route, created ? [registry] : []);
    }
    if (path === "/api/source-registries" && request.method() === "POST") {
      savedPayload = request.postDataJSON();
      created = true;
      return fulfill(route, registry);
    }
    if (path === "/api/source-registries/31/enabled" && request.method() === "PUT") {
      enableRequests.push(Boolean(request.postDataJSON().enabled));
      return fulfill(route, { ...registry, enabled: true });
    }
    if (path === "/api/crawl-tasks") return fulfill(route, []);
    if (path === "/api/ai-queue") return fulfill(route, [waitingBudget]);
    return fulfill(route, null, 404, "测试未配置该接口");
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/public-sources`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.getByText(/新来源保持停用/)).toBeVisible();
  await expect(page.getByText(/原图缓存和自动 AI 均关闭/)).toBeVisible();
  await expect(page.getByText(/必须人工审核后才能发布/)).toBeVisible();
  await expect(page.getByLabel(/允许生成图片候选/)).not.toBeChecked();
  await expect(page.getByLabel(/允许自动 AI/)).not.toBeChecked();

  await page.getByLabel("来源名称").last().fill("健康权威来源");
  await page.getByLabel("完整域名").fill("health.example.gov.cn");
  await page.getByLabel("发现方式").selectOption("RSS");
  await page.getByLabel("主页地址").fill("https://health.example.gov.cn/");
  await page.getByLabel("RSS / Atom 地址").fill("https://health.example.gov.cn/rss.xml");
  await page.getByLabel("每日采集时间").fill("06:30");
  await page.getByLabel("每轮文章上限").fill("8");
  await page.getByLabel("每日文章预算").fill("12");
  await page.getByLabel("每日 Token 预算").fill("48000");
  await page.getByRole("button", { name: "新增运营来源" }).click();

  await expect.poll(() => savedPayload).toBeTruthy();
  expect(savedPayload).toMatchObject({
    discoveryMode: "RSS",
    dailyCrawlTime: "06:30",
    maxArticlesPerRun: 8,
    dailyArticleBudget: 12,
    dailyTokenBudget: 48000,
    allowImageCandidates: false,
    allowAutoAi: false,
  });
  await expect(page.getByText(/已停用 · RSS/)).toBeVisible();
  await expect(page.getByText(/每日 12 篇/)).toBeVisible();
  await expect(page.getByText(/48,000 Token · 自动 AI 关闭/)).toBeVisible();
  await expect(page.getByRole("cell", { name: "等待预算恢复" }).first()).toBeVisible();
  await expect(page.getByText(waitingBudget.reason_summary)).toBeVisible();
  await expect(page.getByRole("button", { name: "等待预算恢复" })).toBeDisabled();
  await expect(page.getByText("已自动执行")).toHaveCount(0);

  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "启用", exact: true }).last().click();
  expect(enableRequests).toEqual([]);
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "启用", exact: true }).last().click();
  await expect.poll(() => enableRequests).toEqual([true]);
});

test("来源、调度和预算页面在 375px 与 1440px 均不横向溢出视口", async ({ page }) => {
  await prepare(page);
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/public-sources") return fulfill(route, []);
    if (path === "/api/source-registries") return fulfill(route, [registry]);
    if (path === "/api/crawl-tasks") return fulfill(route, []);
    if (path === "/api/ai-queue") return fulfill(route, [waitingBudget]);
    return fulfill(route, null, 404, "测试未配置该接口");
  });
  for (const viewport of [
    { width: 375, height: 812 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto(`${institutionUrl}/public-sources`);
    await expect(page.getByRole("heading", { name: "权威来源管理" })).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBeTruthy();
  }
});
