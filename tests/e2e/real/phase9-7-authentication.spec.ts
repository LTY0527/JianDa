import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const platformUsername = process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin";
const platformPassword = process.env.REAL_PLATFORM_ADMIN_PASSWORD;
const residentUsername = process.env.REAL_RESIDENT_USERNAME ?? "demo_chen";
const residentPassword = process.env.REAL_RESIDENT_PASSWORD;
const artifactRoot = path.resolve(
  process.env.JIANDA_FINAL_ACCEPTANCE_ARTIFACT_DIR
    ?? "artifacts/phase9-7-final-commercial-polish/real-e2e",
);

test.describe("Phase 9.7 真实浏览器认证", () => {
  test.beforeAll(() => {
    fs.mkdirSync(artifactRoot, { recursive: true });
  });

  test("平台管理员使用真实表单登录、访问主页面并退出", async ({ page }) => {
    test.skip(!platformPassword, "BLOCKED_BY_CREDENTIALS: 缺少平台管理员真实验收密码");
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/login`);
    await page.getByRole("textbox", { name: "账号", exact: true }).fill(platformUsername);
    await page.getByLabel("密码").fill(platformPassword!);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page).toHaveURL(`${institutionUrl}/`);
    await expect(page.locator(".account")).toContainText("平台管理员");
    const navigation = page.getByRole("navigation", { name: "主导航" });
    for (const label of ["工作台", "内容中心", "采集与来源", "数据概览", "商业运营", "系统记录"]) {
      await expect(navigation.getByRole("link", { name: label, exact: true })).toBeVisible();
    }
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
    test.skip(!residentPassword, "BLOCKED_BY_CREDENTIALS: 缺少居民真实验收密码");
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/profile`);
    await page.getByRole("button", { name: "用户名登录", exact: true }).click();
    await page.getByLabel("用户名").fill(residentUsername);
    await page.getByLabel("密码").fill(residentPassword!);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page).toHaveURL(`${h5Url}/profile`);
    await expect(page.getByRole("heading", { name: "陈阿姨", exact: true })).toBeVisible();
    await expect(page.locator(".profile-hero small")).toContainText("账号 demo_chen");
    await page.screenshot({ path: path.join(artifactRoot, "resident-login.png"), fullPage: false });

    await page.getByRole("button", { name: "退出", exact: true }).click();
    await expect(page).toHaveURL(`${h5Url}/resident/login`);
    await expect(page.getByText("社区里的事，讲得更明白。", { exact: true })).toBeVisible();
  });
});
