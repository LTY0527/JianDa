import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const platformUsername = process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin";
const platformPassword = process.env.REAL_PLATFORM_ADMIN_PASSWORD;
const residentUsername = process.env.REAL_RESIDENT_USERNAME ?? "demo_chen";
const residentPassword = process.env.REAL_RESIDENT_PASSWORD;
const artifactRoot = path.resolve("artifacts/phase9-7-final-commercial-polish/real-e2e");

function requireCredentials() {
  if (!platformPassword || !residentPassword) {
    throw new Error("BLOCKED: CREDENTIAL_MISSING");
  }
}

test.describe("Phase 9.7 真实浏览器认证", () => {
  test.beforeAll(() => {
    requireCredentials();
    fs.mkdirSync(artifactRoot, { recursive: true });
  });

  test("平台管理员使用真实表单登录、访问主页面并退出", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/login`);
    await page.getByRole("textbox", { name: "账号", exact: true }).fill(platformUsername);
    await page.getByLabel("密码").fill(platformPassword!);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page).toHaveURL(`${institutionUrl}/`);
    await expect(page.locator(".account")).toContainText("平台管理员");
    await expect(page.getByRole("navigation", { name: "主导航" }).getByRole("link")).toHaveCount(5);
    await page.screenshot({ path: path.join(artifactRoot, "platform-login.png"), fullPage: false });

    for (const [label, route] of [
      ["内容中心", "/documents"],
      ["采集与来源", "/public-sources"],
      ["数据概览", "/operations"],
      ["系统记录", "/logs"],
    ] as const) {
      await page.getByRole("link", { name: label }).click();
      await expect(page).toHaveURL(`${institutionUrl}${route}`);
    }

    await page.locator(".account").click();
    await expect(page).toHaveURL(`${institutionUrl}/login`);
  });

  test("居民使用真实表单登录、读取个人资料并退出", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/profile`);
    await page.getByLabel("账号").fill(residentUsername);
    await page.getByLabel("密码").fill(residentPassword!);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page.getByText("DEMO 居民账号")).toBeVisible();
    await expect(page.locator(".resident-card p")).toHaveText("宝山区 · 大场镇");
    await page.screenshot({ path: path.join(artifactRoot, "resident-login.png"), fullPage: false });

    await page.getByRole("button", { name: "退出", exact: true }).click();
    await expect(page.getByRole("heading", { name: "居民登录" })).toBeVisible();
  });
});
