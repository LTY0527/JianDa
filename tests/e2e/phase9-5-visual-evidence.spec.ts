import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { authenticateResident } from "./support/residentAuth";

const institutionUrl = process.env.JIANDA_INSTITUTION_TEST_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1";
const stage = process.env.JIANDA_PHASE95_SCREENSHOT_STAGE === "after" ? "after" : "before";
const artifactRoot = path.resolve(
  process.env.JIANDA_PHASE95_ARTIFACT_ROOT ??
    path.join(os.tmpdir(), "jianda-phase9-5-commercial-ux"),
);

async function login(page: Page) {
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill("platform_admin");
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
}

async function capture(page: Page, name: string) {
  const directory = path.join(artifactRoot, stage);
  fs.mkdirSync(directory, { recursive: true });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(directory, name), fullPage: false });
}

async function assertReady(page: Page) {
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(page.getByRole("heading").first()).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
}

test("保存 Phase 9.5 H5 首页视觉证据", async ({ page }) => {
  for (const [width, height, file] of [
    [375, 812, "h5-home-375.png"],
    [390, 844, "h5-home-390.png"],
    [768, 1024, "h5-home-768.png"],
    [1440, 900, "h5-home-1440.png"],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(h5Url);
    await assertReady(page);
    await capture(page, file);
  }
});

test("保存 Phase 9.5 H5 关键任务视觉证据", async ({ page, request }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const itemsResponse = await request.get(`${h5Url}/api/public/items`);
  expect(itemsResponse.ok()).toBeTruthy();
  const items = (await itemsResponse.json()).data as Array<{ slug: string; content_kind?: string }>;
  const detail = items.find((item) => item.content_kind === "SERVICE_NOTICE") || items[0];
  expect(detail).toBeTruthy();

  await page.goto(h5Url);
  await page.getByRole("button", { name: "选择所在地区" }).click();
  await expect(page.getByRole("dialog", { name: "选择所在地区" })).toBeVisible();
  await capture(page, "h5-location-picker-390.png");

  for (const [route, file] of [
    ["/services", "h5-service-directory-390.png"],
    [`/guide/${detail!.slug}`, "h5-detail-390.png"],
    ["/neighborhood", "h5-neighborhood-390.png"],
    ["/reminders", "h5-reminders-390.png"],
  ] as const) {
    await page.goto(`${h5Url}${route}`);
    await assertReady(page);
    await capture(page, file);
  }

  await authenticateResident(page, h5Url);
  await page.goto(`${h5Url}/profile`);
  await expect(page.getByRole("heading", { name: "陈阿姨" })).toBeVisible();
  await capture(page, "h5-profile-390.png");
});

test("保存 Phase 9.5 机构端视觉证据", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await login(page);
  for (const [route, file] of [
    ["/", "admin-dashboard-1440.png"],
    ["/documents", "admin-content-1440.png"],
    ["/public-sources", "admin-auto-collection-1440.png"],
    ["/operations", "admin-analytics-1440.png"],
    ["/community-moderation", "admin-community-moderation-1440.png"],
    ["/documents/16/review", "admin-review-1440.png"],
    ["/documents/40/publish", "admin-publish-preview-1440.png"],
  ] as const) {
    await page.goto(`${institutionUrl}${route}`);
    await expect(page.locator("main")).not.toBeEmpty();
    await assertReady(page);
    if (route.endsWith("/review")) {
      const showAll = page.getByRole("button", { name: /查看全部 \d+ 项/ });
      if (await showAll.isVisible()) await showAll.click();
    }
    await capture(page, file);
  }
});
