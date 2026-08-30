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

async function fulfill(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: 0, message: "成功", data }),
  });
}

async function prepare(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem("jianda_token", "isolated-platform-token");
    localStorage.setItem("jianda_user_info", JSON.stringify(user));
  }, platformUser);
}

const registry = {
  id: 31,
  domain: "health.example.gov.cn",
  source_name: "健康权威来源",
  source_type: "COMMUNITY_HEALTH",
  authority_level: "A",
  enabled: true,
  discovery_mode: "SECTION",
  homepage_url: "https://health.example.gov.cn/",
  section_url: "https://health.example.gov.cn/news",
  daily_crawl_time: "06:30",
  max_articles_per_run: 20,
  allow_image_candidates: true,
  allow_auto_ai: false,
  daily_article_budget: 20,
  daily_token_budget: 100000,
  schedule_mode: "INTERVAL",
  interval_hours: 12,
  schedule_timezone: "Asia/Shanghai",
  recent_days: 7,
  auto_save_draft: true,
  duplicate_strategy: "SKIP",
  max_retries: 3,
  image_usage_policy: "MANUAL_REVIEW",
  auto_approve_images: false,
  image_cache_allowed: false,
  last_status: "SUCCESS",
};

test("未知官网必须先安全预览并由平台管理员确认官方身份", async ({ page }) => {
  await prepare(page);
  let confirmPayload: Record<string, unknown> | undefined;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/public-sources") return fulfill(route, []);
    if (path === "/api/source-registries" && request.method() === "GET") return fulfill(route, [registry]);
    if (path === "/api/crawl-tasks" || path === "/api/ai-queue") return fulfill(route, []);
    if (path === "/api/source-registries/quick-preview") {
      return fulfill(route, {
        original_url: "https://mp.weixin.qq.com/s/example",
        canonical_url: "https://mp.weixin.qq.com/s/example",
        domain: "mp.weixin.qq.com",
        https: true,
        page_title: "社区健康提醒",
        source_name: "浦江健康服务",
        wechat_account_name: "浦江健康服务",
        account_subject: "浦江街道社区卫生服务中心",
        wechat_biz: "MzA-test-account",
        wechat_article: true,
        source_identity_fingerprint: "a".repeat(64),
        source_type_suggestion: "OFFICIAL_WECHAT",
        robots_allowed: true,
        robots_status: "ALLOWED",
        official_verified: false,
      });
    }
    if (path === "/api/source-registries/quick-confirm") {
      confirmPayload = request.postDataJSON();
      return fulfill(route, {
        source: { ...registry, id: 45, source_type: "OFFICIAL_WECHAT" },
        imported: { documentId: 710, aiQueueStatus: "WAITING_APPROVAL" },
      });
    }
    return fulfill(route, null);
  });

  await page.goto(`${institutionUrl}/public-sources`);
  await page.getByRole("button", { name: /高级管理/ }).click();
  await page.getByRole("button", { name: "扫描与导入" }).click();
  await page.getByLabel("公开文章地址").fill("https://mp.weixin.qq.com/s/example");
  await page.getByRole("button", { name: "安全预览来源身份" }).click();
  await expect(page.getByText("浦江街道社区卫生服务中心")).toBeVisible();
  await expect(page.getByText(/共享文章域名/)).toBeVisible();
  await expect(page.getByRole("button", { name: "确认来源并继续" })).toBeDisabled();
  await page.getByLabel("官方性质核对说明").fill("已核对社区卫生服务中心官网公开账号信息");
  await page.getByLabel(/我已核对并确认/).check();
  await page.getByRole("button", { name: "确认来源并继续" }).click();
  await expect(page.getByText(/材料 #710 已创建/)).toBeVisible();
  expect(confirmPayload).toMatchObject({
    sourceType: "OFFICIAL_WECHAT",
    officialConfirmed: true,
    mode: "SAVE_MANUAL_SCAN",
    continueImport: true,
  });
});

test("扫描筛选和批量保存只提交所选未导入 URL", async ({ page }) => {
  await prepare(page);
  let discoverPayload: Record<string, unknown> | undefined;
  let batchPayload: Record<string, unknown> | undefined;
  const candidates = [
    {
      canonical_url: "https://health.example.gov.cn/news/new",
      discovered_url: "https://health.example.gov.cn/news/new",
      title: "老年健康新文章",
      published_time: "2026-07-29T10:00:00+08:00",
      discovery_method: "SECTION",
      content_kind_candidate: "HEALTH_EDUCATION",
      dedup_key: "new",
      imported: false,
      relevance_level: "HIGH",
      relevance_score: 99,
      recommendation_reason: "与居民健康服务高度相关",
    },
    {
      canonical_url: "https://health.example.gov.cn/news/old",
      discovered_url: "https://health.example.gov.cn/news/old",
      title: "已导入文章",
      discovery_method: "SECTION",
      content_kind_candidate: "HEALTH_EDUCATION",
      dedup_key: "old",
      imported: true,
      relevance_level: "LOW",
      relevance_score: 10,
      recommendation_reason: "偏行政公示",
    },
  ];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/public-sources") return fulfill(route, []);
    if (path === "/api/source-registries" && request.method() === "GET") return fulfill(route, [registry]);
    if (path === "/api/crawl-tasks" || path === "/api/ai-queue") return fulfill(route, []);
    if (path.endsWith("/discover-jobs") && request.method() === "POST") {
      discoverPayload = request.postDataJSON();
      return fulfill(route, {
        id: 801,
        source_registry_id: 31,
        source_name: registry.source_name,
        domain: registry.domain,
        original_url: registry.section_url,
        status: "SUCCESS",
        trigger_type: "MANUAL",
        processing_stage: "COMPLETE",
        discovered_count: 2,
        added_count: 1,
        duplicate_count: 1,
        skipped_count: 12,
        failed_count: 0,
        retry_count: 0,
        discoveryResult: {
          sourceId: 31,
          method: "SECTION",
          candidates,
          duplicateCount: 1,
          filtered_external_count: 12,
          filtered_external_domains: ["outside.example"],
          errors: [],
        },
      });
    }
    if (path.endsWith("/collect-batch")) {
      batchPayload = request.postDataJSON();
      return fulfill(route, { jobId: 901, status: "PENDING", total: 1 });
    }
    return fulfill(route, null);
  });

  await page.goto(`${institutionUrl}/public-sources`);
  await page.getByRole("button", { name: /高级管理/ }).click();
  await page.getByRole("button", { name: "扫描与导入" }).click();
  await page.getByLabel("关键词", { exact: true }).fill("健康");
  await page.getByRole("button", { name: "扫描最近文章" }).click();
  await expect(
    page.getByText("已过滤 12 个不属于当前来源范围的外部链接。"),
  ).toHaveCount(1);
  expect(discoverPayload).toMatchObject({
    recentDays: 7,
    maxArticles: 20,
    includeKeywords: "健康",
    onlyUnimported: true,
  });
  await page.getByRole("button", { name: "全选未重复内容" }).click();
  await expect(page.getByLabel("选择老年健康新文章")).toBeChecked();
  await expect(page.getByLabel("选择已导入文章")).toBeDisabled();
  await expect(page.getByText("与居民健康服务高度相关")).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: /批量保存所选/ }).click();
  expect(batchPayload).toEqual({ urls: ["https://health.example.gov.cn/news/new"] });
  await expect(page.getByText(/批量加入任务 #901 已创建/)).toBeVisible();
});

test("历史补图必须先预览再确认执行", async ({ page }) => {
  await prepare(page);
  let executePayload: Record<string, unknown> | undefined;
  let jobPolls = 0;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/public-sources") return fulfill(route, []);
    if (path === "/api/source-registries" && request.method() === "GET") return fulfill(route, [registry]);
    if (path === "/api/crawl-tasks" || path === "/api/ai-queue") return fulfill(route, []);
    if (path === "/api/cover-backfill/preview") {
      return fulfill(route, {
        total: 8,
        byType: { WEB_ARTICLE: 3, PDF: 4, IMAGE: 1 },
        items: [],
      });
    }
    if (path === "/api/runtime-capabilities") {
      return fulfill(route, {
        llmProvider: "external",
        externalModel: "deepseek-v4-flash",
        assistantExternalEnabled: true,
        crawlAutoAiEnabled: true,
        crawlSchedulerEnabled: true,
        dailyArticleLimit: 0,
        dailyTokenLimit: 0,
        amap: { status: "disabled" },
        webSearch: { status: "disabled", provider: "NONE" },
        payment: { available: false, provider: "NONE", message: "测试环境未配置" },
        aiService: {
          service: { status: "ready" },
          llm: { status: "ready", provider: "external", model: "deepseek-v4-flash" },
          ocr: { status: "ready", engine: "fixture" },
          webCollector: { status: "ready" },
        },
      });
    }
    if (path === "/api/cover-backfill/jobs" && request.method() === "POST") {
      executePayload = request.postDataJSON();
      return fulfill(route, {
        jobId: 81,
        status: "PENDING",
        total: 8,
        processed: 0,
        updated: 0,
        candidatesCreated: 0,
        autoApproved: 0,
        failed: 0,
        errors: [],
      });
    }
    if (path === "/api/cover-backfill/jobs/81") {
      jobPolls++;
      return fulfill(route, {
        jobId: 81,
        status: "SUCCEEDED",
        total: 8,
        processed: 8,
        updated: 5,
        candidatesCreated: 3,
        autoApproved: 1,
        failed: 0,
        errors: [],
      });
    }
    return fulfill(route, null);
  });

  await page.goto(`${institutionUrl}/public-sources`);
  await page.getByRole("button", { name: /高级管理/ }).click();
  await page.getByRole("button", { name: "高级自动采集设置" }).click();
  await expect(page.getByRole("button", { name: "执行历史补图" })).toBeDisabled();
  await page.getByRole("button", { name: "预览补图范围" }).click();
  await expect(page.getByText(/网页 3， PDF 4， 图片 1/)).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "执行历史补图" }).click();
  expect(executePayload).toMatchObject({ onlyMissing: true });
  await expect(page.getByText(/已处理 8\/8/)).toBeVisible();
  await expect(page.getByText(/更新 5/)).toBeVisible();
  expect(jobPolls).toBeGreaterThan(0);
  await page.getByRole("button", { name: "AI 等待队列" }).click();
  await expect(page.getByText(/Provider external/)).toBeVisible();
  await expect(page.getByText(/文章 不限/)).toBeVisible();
});
