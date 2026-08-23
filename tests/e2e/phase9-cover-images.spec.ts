import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_TEST_URL || process.env.H5_E2E_URL || "http://127.0.0.1:5174";

const items = [
  {
    id: 901,
    slug: "news-cover-fallback",
    title: "三伏天老年人健康提醒",
    summary: "高温天气注意补水，身体不适时及时就医。",
    category: "健康",
    source_name: "权威健康来源",
    source_url: "https://official.example/article",
    published_at: "2026-07-26T08:00:00+08:00",
    content_kind: "HEALTH_EDUCATION",
    cover_image_url: "https://images.example/missing-cover.jpg",
    cover_image_type: "ORIGINAL_COVER",
    image_alt_text: "高温天气健康提示",
    image_source_name: "权威健康来源",
    importance: 99,
  },
  {
    id: 902,
    slug: "news-category-default",
    title: "城乡养老服务网络建设",
    summary: "介绍城乡三级养老服务网络建设进展。",
    category: "养老政策",
    source_name: "权威政策来源",
    source_url: "https://official.example/policy",
    published_at: "2026-07-25T08:00:00+08:00",
    content_kind: "POLICY_NEWS",
    cover_image_type: "CATEGORY_DEFAULT",
    image_alt_text: "养老政策分类默认插图",
    image_source_name: "简达本地分类默认图",
    importance: 90,
  },
  {
    id: 903,
    slug: "news-health-default",
    title: "老年人科学运动提示",
    summary: "结合身体情况选择适合自己的活动强度。",
    category: "健康",
    source_name: "权威健康来源",
    source_url: "https://official.example/health-2",
    published_at: "2026-07-24T08:00:00+08:00",
    content_kind: "HEALTH_EDUCATION",
    cover_image_type: "CATEGORY_DEFAULT",
    image_alt_text: "健康科普分类插图",
    image_source_name: "简达本地分类默认图",
    importance: 80,
  },
];

test.beforeEach(async ({ page }) => {
  await page.route("**/api/public/items?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify({ code: 0, message: "ok", data: items }),
    }),
  );
  await page.route("https://images.example/**", (route) =>
    route.fulfill({ status: 404, body: "" }),
  );
});

test("broken hero cover becomes text while category defaults stay as text feed", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(h5Url);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);

  const featured = page.locator(".commercial-hero");
  await expect(featured).toHaveClass(/commercial-hero--text/);
  await expect(featured.locator("img")).toHaveCount(0);
  await expect(featured.getByRole("heading")).toContainText("三伏天老年人健康提醒");

  const feedEntries = page.locator(".mixed-feed .feed-entry");
  await expect(feedEntries).toHaveCount(2);
  await expect(feedEntries.locator("img")).toHaveCount(0);
  const beforeReload = await feedEntries.evaluateAll((entries) =>
    entries.map((entry) => entry.className),
  );
  await page.reload();
  await expect(page.locator(".mixed-feed .feed-entry")).toHaveCount(beforeReload.length);
  const afterReload = await page.locator(".mixed-feed .feed-entry").evaluateAll((entries) =>
    entries.map((entry) => entry.className),
  );
  expect(afterReload).toEqual(beforeReload);
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    )
    .toBeTruthy();
  await featured.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-phase9-cover-mobile.png"),
    fullPage: true,
  });
  expect(consoleErrors.length).toBeGreaterThanOrEqual(1);
  expect(new Set(consoleErrors)).toEqual(
    new Set([
      "Failed to load resource: the server responded with a status of 404 (Not Found)",
    ]),
  );
  expect(pageErrors).toEqual([]);
});

test("news detail keeps the official article URL separate from its cover", async ({
  page,
}) => {
  const officialUrl = "https://official.example/article";
  const coverUrl = "https://images.example/article-cover.jpg";
  await page.route("**/api/public/items/news-cover-fallback", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify({
        code: 0,
        message: "ok",
        data: {
          ...items[0],
          canonical_url: officialUrl,
          source_url: officialUrl,
          cover_image_url: coverUrl,
          original_page_available: true,
          page_count: 1,
          fields: [],
          generated: {
            SUMMARY: [
              "高温天气要及时补水。",
              "身体不适时不要硬扛。",
              "持续不适时及时就医。",
            ],
            STEP_CARDS: [],
            RISK_WARNING: ["内容仅作健康科普，不能替代医生诊断。"],
            TERM_EXPLANATION: {},
          },
        },
      }),
    }),
  );
  await page.route(coverUrl, (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      path: path.resolve("apps/user-h5/public/images/defaults/health.svg"),
    }),
  );
  await page.addInitScript(() => {
    (window as any).__openedUrls = [];
    window.open = ((url?: string | URL) => {
      (window as any).__openedUrls.push(String(url));
      return null;
    }) as typeof window.open;
  });
  page.on("dialog", (dialog) => dialog.accept());

  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`${h5Url}/news/news-cover-fallback`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("h1")).toContainText("三伏天老年人健康提醒");
  await expect(page.locator(".article-head__cover")).toHaveAttribute("src", coverUrl);
  const officialButton = page.locator(".official-source-link");
  await expect(officialButton).toContainText(officialUrl);
  await officialButton.click();
  await expect
    .poll(() => page.evaluate(() => (window as any).__openedUrls))
    .toEqual([officialUrl]);
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-phase9-detail-mobile.png"),
    fullPage: true,
  });
});
