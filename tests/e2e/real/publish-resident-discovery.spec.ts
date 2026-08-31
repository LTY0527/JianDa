import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const artifactRoot = path.resolve("artifacts/publish-to-resident-discovery-20260831");
const tracked = {
  documentId: 127,
  publishedItemId: 87,
  slug: "guide-127",
  title: "upload",
  regionCode: "310113109",
  bodyKeyword: "青禾一村广场",
};

test("REAL 机构发布内容可由正确地区居民立即发现和检索", async ({ page, request }) => {
  test.setTimeout(180_000);
  fs.mkdirSync(artifactRoot, { recursive: true });
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  const login = await request.post(`${institutionUrl}/api/auth/login`, {
    data: {
      username: process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin",
      password: process.env.REAL_PLATFORM_ADMIN_PASSWORD ?? "Jianda@123",
    },
  });
  expect(login.ok()).toBeTruthy();
  const token = String((await login.json()).data.token);
  expect(token.length).toBeGreaterThan(20);

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/login`);
  await page.evaluate((value) => localStorage.setItem("jianda_token", value), token);
  await page.goto(`${institutionUrl}/published`, { waitUntil: "networkidle" });
  await page.getByPlaceholder("搜索标题").fill(tracked.title);
  await expect(page.getByRole("cell", { name: tracked.title, exact: true })).toBeVisible();
  await expect(page.getByText("已发布", { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "01_institution_publish_success.png"), fullPage: false });

  const correctItems = await request.get(`${h5Url}/api/public/items`, { params: { regionCode: tracked.regionCode } });
  expect(correctItems.ok()).toBeTruthy();
  expect((await correctItems.json()).data.some((item: { slug: string }) => item.slug === tracked.slug)).toBeTruthy();
  await page.goto(`${h5Url}/api/public/items?regionCode=${tracked.regionCode}`);
  await expect(page.locator("body")).toContainText(tracked.slug);
  await page.screenshot({ path: path.join(artifactRoot, "02_public_items_contains_new_post.png"), fullPage: false });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url);
  await page.evaluate(() => localStorage.setItem("jianda_region", JSON.stringify({
    province: "上海市", city: "上海市", district: "宝山区", street_or_town: "顾村镇", region_code: "310113109",
  })));
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByRole("button", { name: "选择所在地区" })).toContainText("顾村镇");
  const homePublishedItem = page.getByText(tracked.title, { exact: true }).first();
  await expect(homePublishedItem).toBeVisible();

  await page.waitForTimeout(5_100);
  const resumedItems = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/public/items" && response.request().method() === "GET",
  );
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  expect((await resumedItems).ok()).toBeTruthy();
  await homePublishedItem.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(artifactRoot, "03_home_new_post_visible_390x844.png"), fullPage: false });

  await page.goto(`${h5Url}/search?q=${encodeURIComponent(tracked.title)}`, { waitUntil: "networkidle" });
  await expect(page.getByText(tracked.title, { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "04_search_unique_keyword_found.png"), fullPage: false });

  const searchInput = page.getByPlaceholder("输入您想了解的内容");
  const bodySearchResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/public/search" && url.searchParams.get("keyword") === tracked.bodyKeyword;
  });
  await searchInput.fill(tracked.bodyKeyword);
  expect((await bodySearchResponse).ok()).toBeTruthy();
  await expect(page.getByText(tracked.title, { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "05_search_body_keyword_found.png"), fullPage: false });

  await page.getByText(tracked.title, { exact: true }).first().click();
  await expect(page).toHaveURL(new RegExp(`/news/${tracked.slug}$`));
  await expect(page.getByRole("heading", { name: tracked.title, exact: true })).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "06_new_post_detail.png"), fullPage: false });

  const wrongList = await request.get(`${h5Url}/api/public/items`, { params: { regionCode: "310113102" } });
  expect((await wrongList.json()).data.some((item: { slug: string }) => item.slug === tracked.slug)).toBeFalsy();
  const wrongSearch = await request.get(`${h5Url}/api/public/search`, {
    params: { keyword: tracked.title, regionCode: "310113102" },
  });
  expect((await wrongSearch.json()).data.some((item: { slug: string }) => item.slug === tracked.slug)).toBeFalsy();
  const wrongDetail = await request.get(`${h5Url}/api/public/items/${tracked.slug}`, { params: { regionCode: "310113102" } });
  expect(wrongDetail.status()).toBe(404);
  const unclassifiedDetail = await request.get(`${h5Url}/api/public/items/news-125`, { params: { regionCode: tracked.regionCode } });
  expect(unclassifiedDetail.status()).toBe(404);

  await page.goto(h5Url);
  await page.getByRole("button", { name: "选择所在地区" }).click();
  await page.getByRole("button", { name: /大场镇/ }).click();
  await page.goto(`${h5Url}/search?q=${encodeURIComponent(tracked.title)}`, { waitUntil: "networkidle" });
  await expect(page.getByText(tracked.title, { exact: true })).toHaveCount(0);
  await expect(page.getByText("平台资料暂未命中")).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "07_wrong_region_not_visible.png"), fullPage: false });

  expect(consoleErrors).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});
