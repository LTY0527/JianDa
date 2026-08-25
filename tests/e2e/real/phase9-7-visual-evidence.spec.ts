import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const platformUsername = process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin";
const platformPassword = process.env.REAL_PLATFORM_ADMIN_PASSWORD;
const captureMode = process.env.JIANDA_PHASE9_7_CAPTURE;
const artifactRoot = path.resolve("artifacts/phase9-7-final-commercial-polish");

async function platformLogin(page: import("@playwright/test").Page) {
  if (!platformPassword) throw new Error("BLOCKED: CREDENTIAL_MISSING");
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(platformUsername);
  await page.getByLabel("密码").fill(platformPassword);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
}

test.describe("Phase 9.7 真实视觉证据", () => {
  test.skip(!captureMode, "仅在显式指定 before/after 证据模式时运行");

  test("保存 H5 与机构端真实页面", async ({ page }) => {
    const output = path.join(artifactRoot, captureMode!);
    fs.mkdirSync(output, { recursive: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(h5Url);
    await expect(page.getByRole("heading").first()).toBeVisible();
    await page.screenshot({ path: path.join(output, "h5-home-390.png"), fullPage: true });
    await page.screenshot({ path: path.join(output, "h5-home-recommend-390.png"), fullPage: true });

    for (const [channel, file] of [
      ["大场", "h5-home-dachang-390.png"],
      ["健康", "h5-home-health-390.png"],
      ["办事", "h5-home-services-390.png"],
    ] as const) {
      await page.getByRole("button", { name: channel, exact: true }).click();
      await expect(page.getByRole("heading", { name: `${channel}内容` })).toBeVisible();
      await page.screenshot({ path: path.join(output, file), fullPage: true });
    }

    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(h5Url);
    await page.screenshot({ path: path.join(output, "h5-home-recommend-375.png"), fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(h5Url);
    await page.screenshot({ path: path.join(output, "h5-home-1440.png"), fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/search`);
    await expect(page.getByRole("heading", { name: "搜索" })).toBeVisible();
    await page.screenshot({ path: path.join(output, "h5-search-390.png"), fullPage: true });

    await page.goto(`${h5Url}/assistant`);
    await expect(page.getByRole("heading", { name: "简达助手" })).toBeVisible();
    await page.screenshot({ path: path.join(output, "h5-assistant-390.png"), fullPage: true });

    await page.goto(`${h5Url}/neighborhood`);
    await page.screenshot({ path: path.join(output, "h5-neighborhood-390.png"), fullPage: true });
    await page.goto(`${h5Url}/profile`);
    await page.screenshot({ path: path.join(output, "h5-profile-390.png"), fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await platformLogin(page);
    await page.screenshot({ path: path.join(output, "admin-dashboard-1440.png"), fullPage: true });
    await page.getByRole("link", { name: "采集与来源", exact: true }).click();
    await page.screenshot({ path: path.join(output, "admin-sources-1440.png"), fullPage: true });
    await page.screenshot({ path: path.join(output, "admin-sources-simple-1440.png"), fullPage: true });
    await page.getByRole("button", { name: "来源健康状态说明" }).first().click();
    await expect(page.getByRole("tooltip")).toBeVisible();
    await page.screenshot({ path: path.join(output, "admin-source-help-tip-1440.png"), fullPage: true });
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: /高级管理/ }).click();
    await expect(page.locator(".collection-advanced")).toBeVisible();
    await page.screenshot({ path: path.join(output, "admin-sources-advanced-1440.png"), fullPage: true });
    await page.getByRole("link", { name: "内容中心", exact: true }).click();
    await page.screenshot({ path: path.join(output, "admin-content-1440.png"), fullPage: true });
    await page.screenshot({ path: path.join(output, "admin-content-center-1440.png"), fullPage: true });
  });
});
