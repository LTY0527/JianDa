import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const artifactRoot = path.resolve(
  process.env.JIANDA_FINAL_ACCEPTANCE_ARTIFACT_DIR
    ?? "artifacts/phase9-9-3-final",
);

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

test("REAL 平台管理员可查看六项运行能力且不暴露秘密", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${institutionUrl}/login`, { waitUntil: "domcontentloaded" });
  await page.getByRole("textbox", { name: "账号", exact: true }).fill("platform_admin");
  await page.getByRole("textbox", { name: "密码", exact: true }).fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).not.toHaveURL(/\/login$/, { timeout: 10_000 });
  await page.goto(`${institutionUrl}/public-sources`, { waitUntil: "networkidle" });

  const capability = page.getByRole("region", { name: "运行能力诊断" });
  await expect(capability).toBeVisible();
  for (const name of ["高德地图", "DeepSeek", "联网搜索", "网页采集", "OCR", "支付测试"]) {
    await expect(capability.getByText(name, { exact: true })).toBeVisible();
  }
  await expect(capability).not.toContainText(/Bearer|Authorization|API Key|密码/);
  await expect(capability.getByText("联网搜索").locator("..")).toContainText(/可用|未启用/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(consoleErrors).toEqual([]);

  await capability.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(artifactRoot, "admin-runtime-capabilities-1440.png"), fullPage: false });
});
