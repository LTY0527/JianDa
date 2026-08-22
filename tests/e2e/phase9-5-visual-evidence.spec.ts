import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

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

test("保存 Phase 9.5 H5 首页视觉证据", async ({ page }) => {
  for (const [width, height, file] of [
    [375, 812, "h5-home-375.png"],
    [1440, 900, "h5-home-1440.png"],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(h5Url);
    await expect(page.locator("#app")).not.toBeEmpty();
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
    await expect(page.getByRole("heading").first()).toBeVisible();
    await capture(page, file);
  }
});

test("保存 Phase 9.5 机构端视觉证据", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await login(page);
  for (const [route, file] of [
    ["/", "admin-dashboard-1440.png"],
    ["/documents", "admin-content-1440.png"],
    ["/public-sources", "admin-auto-collection-1440.png"],
  ] as const) {
    await page.goto(`${institutionUrl}${route}`);
    await expect(page.locator("main")).not.toBeEmpty();
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
    await expect(page.getByRole("heading").first()).toBeVisible();
    await capture(page, file);
  }
});

