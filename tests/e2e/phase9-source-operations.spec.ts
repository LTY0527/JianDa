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

const runtimeCapabilities = {
  crawlAutoAiEnabled: false,
  crawlSchedulerEnabled: false,
  llmProvider: "mock",
  externalModel: "",
  assistantExternalEnabled: false,
  dailyArticleLimit: 5,
  dailyTokenLimit: 50000,
  amap: { status: "disabled", message: "测试环境未配置" },
  webSearch: { status: "disabled", provider: "NONE", message: "测试环境未配置" },
  payment: { available: false, provider: "NONE", message: "测试环境未配置" },
  aiService: {
    service: { status: "ready" },
    llm: { status: "ready", provider: "mock", model: "" },
    ocr: { status: "ready", engine: "fixture" },
    webCollector: { status: "ready" },
  },
};

async function fulfill(route: Route, data: unknown, status = 200, message = "成功") {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: status === 200 ? 0 : status, message, data }),
  });
}

async function prepare(page: Page) {
  page.on("pageerror", (error) => console.error(`PAGE_ERROR: ${error.message}`));
  page.on("response", (response) => {
    if (response.status() >= 400) console.error(`HTTP_${response.status()}: ${response.url()}`);
  });
  page.on("console", (message) => {
    if (message.type() === "error") console.error(`CONSOLE_ERROR: ${message.text()}`);
  });
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
  await page.route("**/*", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (!path.startsWith("/api/")) return route.continue();
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
    if (path === "/api/runtime-capabilities") return fulfill(route, runtimeCapabilities);
    return fulfill(route, null, 404, "测试未配置该接口");
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/public-sources`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.getByRole("heading", { name: "采集与来源" })).toBeVisible();
  await expect(page.getByText("来源核验、扫描范围、AI 预算和任务记录")).toBeVisible();
  await page.getByRole("button", { name: /高级管理/ }).click();
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
  await expect(page.getByText(/每日文章 12 篇/)).toBeVisible();
  await expect(page.getByText(/Token 48,000 Token · 当前来源自动 AI 未允许/)).toBeVisible();
  await page.getByRole("button", { name: "AI 等待队列" }).click();
  await expect(page.getByRole("cell", { name: "等待预算恢复" }).first()).toBeVisible();
  await expect(page.getByText(waitingBudget.reason_summary)).toBeVisible();
  await expect(page.getByRole("button", { name: "等待预算恢复" })).toBeDisabled();
  await expect(page.getByText("已自动执行")).toHaveCount(0);

  await page.getByRole("button", { name: "来源列表" }).click();
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "启用", exact: true }).last().click();
  expect(enableRequests).toEqual([]);
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "启用", exact: true }).last().click();
  await expect.poll(() => enableRequests).toEqual([true]);
});

test("来源、调度和预算页面在 375px 与 1440px 均不横向溢出视口", async ({ page }) => {
  await prepare(page);
  await page.route("**/*", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/public-sources") return fulfill(route, []);
    if (path === "/api/source-registries") return fulfill(route, [registry]);
    if (path === "/api/crawl-tasks") return fulfill(route, []);
    if (path === "/api/ai-queue") return fulfill(route, [waitingBudget]);
    if (path === "/api/runtime-capabilities") return fulfill(route, runtimeCapabilities);
    return fulfill(route, null, 404, "测试未配置该接口");
  });
  for (const viewport of [
    { width: 375, height: 812 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto(`${institutionUrl}/public-sources`);
    await expect(page.getByRole("heading", { name: "采集与来源" })).toBeVisible();
    await expect(page.getByText("健康权威来源")).toBeVisible();
    const sourceCard = page.locator(".source-card").first();
    await expect(sourceCard.getByRole("button", { name: "立即检查" })).toBeVisible();
    await expect(sourceCard.getByRole("link", { name: "查看新内容" })).toBeVisible();
    await expect(sourceCard.getByRole("button", { name: "更多" })).toBeVisible();
    await expect(sourceCard.getByRole("checkbox")).toHaveCount(0);
    await expect(sourceCard.getByText("自动更新已关闭")).toBeVisible();
    if (viewport.width === 375) {
      const help = sourceCard.getByRole("button", { name: "自动更新说明" });
      await help.click();
      await expect(page.getByRole("tooltip")).toContainText("不等于自动发布");
      await page.keyboard.press("Escape");
      await expect(page.getByRole("tooltip")).toHaveCount(0);
      await expect(help).toBeFocused();
    }
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBeTruthy();
    await page.getByRole("button", { name: /高级管理/ }).click();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBeTruthy();
  }
});

test("立即检查进入独立进度页并引导加入内容中心", async ({ page }) => {
  await prepare(page);
  const enabledRegistry = { ...registry, enabled: true, allow_image_candidates: true };
  const candidate = {
    discovered_url: "https://health.example.gov.cn/article/1",
    canonical_url: "https://health.example.gov.cn/article/1",
    title: "老年健康科普文章",
    published_time: "2026-07-29T10:00:00+08:00",
    discovery_method: "RSS",
    discovery_page: enabledRegistry.rss_url,
    content_kind_candidate: "HEALTH_EDUCATION",
    dedup_key: "article-1",
  };
  const calls: string[] = [];
  let jobReads = 0;
  await page.route("**/*", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/public-sources") return fulfill(route, []);
    if (path === "/api/source-registries" && request.method() === "GET") {
      return fulfill(route, [enabledRegistry]);
    }
    if (path === "/api/crawl-tasks") return fulfill(route, []);
    if (path === "/api/ai-queue") return fulfill(route, []);
    if (path === "/api/runtime-capabilities") return fulfill(route, runtimeCapabilities);
    if (path === "/api/source-registries/31/discover-jobs" && request.method() === "POST") {
      calls.push("start");
      return fulfill(route, {
        id: 801, source_registry_id: 31, source_name: enabledRegistry.source_name,
        domain: enabledRegistry.domain, original_url: enabledRegistry.rss_url,
        status: "PENDING", trigger_type: "MANUAL", processing_stage: "CONNECT",
        discovered_count: 0, added_count: 0, duplicate_count: 0, skipped_count: 0,
        failed_count: 0, retry_count: 0, progress_message: "正在连接官网",
      });
    }
    if (path === "/api/source-registries/discover-jobs/801") {
      jobReads += 1;
      if (jobReads === 1) return fulfill(route, {
        id: 801, source_registry_id: 31, source_name: enabledRegistry.source_name,
        domain: enabledRegistry.domain, original_url: enabledRegistry.rss_url,
        status: "RUNNING", trigger_type: "MANUAL", processing_stage: "ARTICLE_PARSE",
        discovered_count: 1, added_count: 0, duplicate_count: 0, skipped_count: 0,
        failed_count: 0, retry_count: 0, progress_message: "正在识别文章",
      });
      return fulfill(route, {
        id: 801, source_registry_id: 31, source_name: enabledRegistry.source_name,
        domain: enabledRegistry.domain, original_url: enabledRegistry.rss_url,
        status: "SUCCESS", trigger_type: "MANUAL", processing_stage: "COMPLETE",
        discovered_count: 1, added_count: 1, duplicate_count: 0, skipped_count: 174,
        failed_count: 0, retry_count: 0,
        discoveryResult: {
          sourceId: 31, method: "RSS", candidates: [candidate], duplicateCount: 0,
          errors: [], filtered_external_count: 20, filtered_navigation_count: 154,
        },
      });
    }
    if (path.endsWith("/collect")) {
      calls.push("collect");
      return fulfill(route, {
        documentId: 601,
        imageReviewRequired: true,
        aiQueueStatus: "WAITING_APPROVAL",
      });
    }
    return fulfill(route, null, 404, "测试未配置该接口");
  });

  await page.goto(`${institutionUrl}/public-sources`);
  await page.getByRole("button", { name: "立即检查" }).click();
  await expect(page).toHaveURL(/\/public-sources\/31\/check\/801/);
  await expect(page.getByRole("heading", { name: "正在识别文章" })).toBeVisible();
  await expect(page.getByText("检查完成")).toBeVisible({ timeout: 5000 });
  await expect(page.getByText(candidate.title)).toBeVisible();
  await expect(page.getByText(/已合并过滤 174 条/)).toBeVisible();
  expect(calls).toEqual(["start"]);

  await page.getByRole("button", { name: "加入内容中心", exact: true }).click();
  await expect(page.getByText(/材料 #601 已加入内容中心/)).toBeVisible();
  await expect(page.getByRole("button", { name: "立即处理" })).toBeVisible();
  await expect(page.getByRole("link", { name: "返回来源" }).last()).toHaveAttribute("href", "/public-sources");
  expect(calls).toEqual(["start", "collect"]);
});
