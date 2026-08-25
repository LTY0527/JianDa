import { expect, test, type Page, type Route } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

const registry = {
  id: 41, domain: "service.example.gov.cn", source_name: "公共服务来源",
  source_type: "GOVERNMENT", authority_level: "A", enabled: true,
  discovery_mode: "SITEMAP", homepage_url: "https://service.example.gov.cn/",
  daily_crawl_time: "05:00", max_articles_per_run: 10,
  allow_image_cache: false, allow_image_candidates: false,
  allow_auto_crawl: true, allow_auto_ai: false, requires_manual_review: true,
  daily_article_budget: 20, daily_token_budget: 60000, last_status: "PARTIAL_SUCCESS",
};

const partialJob = {
  id: 610, source_registry_id: 41, source_name: "公共服务来源",
  domain: "service.example.gov.cn", original_url: "https://service.example.gov.cn/",
  status: "PARTIAL_SUCCESS", trigger_type: "SCHEDULED", processing_stage: "DONE",
  discovered_count: 7, added_count: 3, duplicate_count: 2, skipped_count: 1,
  failed_count: 1, retry_count: 0, last_error: "1 条地址抓取失败",
  started_at: "2026-07-29T05:00:00+08:00", finished_at: "2026-07-29T05:02:00+08:00",
};

const runningJob = {
  ...partialJob, id: 611, status: "RUNNING", processing_stage: "FETCHING",
  discovered_count: 2, added_count: 0, duplicate_count: 0, skipped_count: 0,
  failed_count: 0, last_error: "",
};

const errors = [
  {
    id: 701, crawl_job_id: 610, source_registry_id: 41,
    failed_url: "https://service.example.gov.cn/a",
    processing_stage: "FETCH", error_code: "UPSTREAM_TIMEOUT",
    error_summary: "上游页面响应超时，请稍后重试。", retryable: true,
    retry_count: 0, next_retry_at: "2026-07-29T06:00:00+08:00",
    created_at: "2026-07-29T05:01:00+08:00", updated_at: "2026-07-29T05:01:00+08:00",
  },
  {
    id: 702, crawl_job_id: 610, source_registry_id: 41,
    failed_url: "https://service.example.gov.cn/b",
    processing_stage: "ROBOTS", error_code: "ROBOTS_DENIED",
    error_summary: "来源网站不允许自动采集。", retryable: false, retry_count: 0,
    created_at: "2026-07-29T05:01:00+08:00", updated_at: "2026-07-29T05:01:00+08:00",
  },
  {
    id: 703, crawl_job_id: 610, source_registry_id: 41,
    failed_url: "https://service.example.gov.cn/c",
    processing_stage: "FETCH", error_code: "HTTP_503",
    error_summary: "该错误已经处理完成。", retryable: true, retry_count: 1,
    resolved_at: "2026-07-29T05:30:00+08:00",
    created_at: "2026-07-29T05:01:00+08:00", updated_at: "2026-07-29T05:30:00+08:00",
  },
];

async function json(route: Route, data: unknown, status = 200, message = "成功") {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: status === 200 ? 0 : status, message, data }),
  });
}

async function prepare(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "isolated-platform-token");
    localStorage.setItem("jianda_user_info", JSON.stringify({
      id: 1, organizationId: 1, username: "platform_admin",
      displayName: "平台管理员", role: "PLATFORM_ADMIN", organizationName: "简达平台",
    }));
  });
}

test("PARTIAL_SUCCESS 错误队列只允许重试未解决的可重试项", async ({ page }) => {
  await prepare(page);
  const singleRetryIds: number[] = [];
  const batchRetryIds: number[] = [];
  const cancelledIds: number[] = [];
  let conflictNext = false;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/public-sources") return json(route, []);
    if (path === "/api/source-registries") return json(route, [registry]);
    if (path === "/api/crawl-tasks" && request.method() === "GET") return json(route, [partialJob, runningJob]);
    if (path === "/api/crawl-tasks/610" && request.method() === "GET") {
      return json(route, { ...partialJob, errors });
    }
    if (path === "/api/crawl-tasks/errors/701/retry") {
      singleRetryIds.push(701);
      if (conflictNext) {
        return json(route, { stack: "internal.StackTrace must never render" }, 409, "任务正在由其他执行器处理，请稍后再试。");
      }
      return json(route, { jobId: 612 });
    }
    if (path === "/api/crawl-tasks/610/retry-failures") {
      batchRetryIds.push(610);
      return json(route, { jobIds: [613], count: 1 });
    }
    if (path === "/api/crawl-tasks/611/cancel") {
      cancelledIds.push(611);
      return json(route, null);
    }
    if (path === "/api/ai-queue") return json(route, []);
    if (path === "/api/runtime-capabilities") {
      return json(route, {
        llmProvider: "mock",
        assistantExternalEnabled: false,
        crawlAutoAiEnabled: false,
        crawlSchedulerEnabled: false,
        dailyArticleLimit: 0,
        dailyTokenLimit: 0,
      });
    }
    return json(route, null, 404, "测试未配置该接口");
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/public-sources`);
  await page.getByRole("button", { name: /高级管理/ }).click();
  await page.getByRole("button", { name: "采集任务" }).click();
  const partialRow = page.locator("tbody tr").filter({ hasText: "#610" });
  await expect(partialRow).toContainText("部分成功");
  await expect(partialRow).toContainText("发现 7 · 新增 3");
  await expect(partialRow).toContainText("重复 2 · 跳过 1 · 失败 1");
  await partialRow.getByRole("button", { name: "详情" }).click();

  await expect(page.getByText("上游页面响应超时，请稍后重试。")).toBeVisible();
  await expect(page.getByText("来源网站不允许自动采集。")).toBeVisible();
  await expect(page.getByText("该错误已经处理完成。")).toBeVisible();
  await expect(page.getByRole("button", { name: "单条重试" })).toHaveCount(1);
  await expect(page.getByRole("button", { name: "整批重试可重试项" })).toBeVisible();

  await page.getByRole("button", { name: "单条重试" }).click();
  await expect.poll(() => singleRetryIds).toEqual([701]);

  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "整批重试可重试项" }).click();
  expect(batchRetryIds).toEqual([]);
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "整批重试可重试项" }).click();
  await expect.poll(() => batchRetryIds).toEqual([610]);

  const runningRow = page.locator("tbody tr").filter({ hasText: "#611" });
  page.once("dialog", (dialog) => dialog.dismiss());
  await runningRow.getByRole("button", { name: "取消" }).click();
  expect(cancelledIds).toEqual([]);
  page.once("dialog", (dialog) => dialog.accept());
  await runningRow.getByRole("button", { name: "取消" }).click();
  await expect.poll(() => cancelledIds).toEqual([611]);

  conflictNext = true;
  await page.getByRole("button", { name: "单条重试" }).click();
  await expect(page.getByText("任务正在由其他执行器处理，请稍后再试。")).toBeVisible();
  await expect(page.getByText(/internal\.StackTrace/)).toHaveCount(0);
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
});
