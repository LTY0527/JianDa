import { expect, test, type Page } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

const metrics = {
  authoritySourceCount: 3,
  discoveredArticleCount: 28,
  successfulCrawlCount: 19,
  duplicateCount: 4,
  waitingReviewCount: 5,
  publishedCount: 16,
  failedCount: 2,
  averageProcessingMs: 1800,
  aiRequestCount: 12,
  aiTokenCount: 32000,
  aiSuccessRate: 91.7,
  viewCount: 80,
  favoriteCount: 9,
  assistantQueryCount: 7,
  citedAnswerRate: 85.7,
  manualEditRate: 20,
  todayDiscoveredCount: 8,
  todayCollectedCount: 5,
  todayDuplicateCount: 2,
  todayFailedCount: 1,
  pendingImageCandidateCount: 3,
  averageCrawlMs: 1250,
  averageAiMs: 2680,
  tokenBudgetTotal: 50000,
  tokenUsedToday: 12300,
  sources: [{
    id: 1,
    source_name: "国家卫生健康委员会",
    domain: "www.nhc.gov.cn",
    enabled: true,
    last_status: "PARTIAL_SUCCESS",
    last_crawled_at: "2026-07-29T09:00:00+08:00",
    next_run_at: "2026-07-30T03:30:00+08:00",
    last_error: "1 个文章链接暂时无法访问",
    failure_count: 1,
  }],
  aiQueueByStatus: [{
    status: "WAITING_APPROVAL",
    item_count: 2,
    estimated_tokens: 4800,
    actual_tokens: 0,
  }],
  recentErrors: [{
    id: 10,
    source_name: "国家卫生健康委员会",
    error_code: "FETCH_TIMEOUT",
    error_summary: "文章页面响应超时",
    processing_stage: "FETCH",
    failed_url: "https://www.nhc.gov.cn/example",
    retryable: true,
    retry_count: 1,
    created_at: "2026-07-29T09:01:00+08:00",
  }],
};

async function prepare(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "operations-test-token");
    localStorage.setItem("jianda_user_info", JSON.stringify({
      id: 1,
      organizationId: 1,
      username: "platform_admin",
      displayName: "平台管理员",
      role: "PLATFORM_ADMIN",
      organizationName: "简达平台",
    }));
  });
  await page.route("**/api/operation-metrics", (route) =>
    route.fulfill({
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify({ code: 0, message: "成功", data: metrics }),
    }),
  );
}

test("独立运营看板展示真实接口返回的来源、队列、预算和错误", async ({ page }) => {
  await prepare(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/operations`);

  await expect(page).toHaveURL(/\/operations$/);
  await expect(page.getByRole("heading", { name: "平台运营看板" })).toBeVisible();
  await expect(page.getByText("今日发现").locator("..")).toContainText("8");
  await expect(page.getByText("12,300 / 50,000")).toBeVisible();
  await expect(page.getByText("国家卫生健康委员会").first()).toBeVisible();
  await expect(page.getByText("等待人工批准")).toBeVisible();
  await expect(page.getByText("文章页面响应超时")).toBeVisible();
  await expect(page.locator("body")).not.toContainText("Bearer");
  await expect(page.locator("body")).not.toContainText("Exception");
});

test("运营看板在手机和桌面宽度无横向溢出", async ({ page }) => {
  await prepare(page);
  for (const viewport of [
    { width: 375, height: 812 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto(`${institutionUrl}/operations`);
    await expect(page.getByRole("heading", { name: "平台运营看板" })).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBeTruthy();
  }
});
