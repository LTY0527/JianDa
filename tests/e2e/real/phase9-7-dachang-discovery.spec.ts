import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const username = process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin";
const password = process.env.REAL_PLATFORM_ADMIN_PASSWORD;
const sourceName = "宝山区政府信息公开·大场镇";
const artifactRoot = path.resolve("artifacts/phase9-7-final-commercial-polish/real-e2e");

test("大场镇来源通过真实页面完成发现并在有候选时影子采集", async ({ page }) => {
  test.setTimeout(180_000);
  test.skip(process.env.JIANDA_ALLOW_REAL_DISCOVERY !== "1", "需要显式开启真实官方来源发现");
  if (!password) throw new Error("BLOCKED: CREDENTIAL_MISSING");
  fs.mkdirSync(artifactRoot, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(username);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("link", { name: "采集与来源", exact: true }).click();

  const card = page.locator(".source-card").filter({ hasText: sourceName });
  await expect(card).toBeVisible();
  const wasDisabled = await card.getByText("自动更新已关闭").count() > 0;
  try {
    if (wasDisabled) {
      await card.getByRole("button", { name: "更多" }).click();
      const row = page.locator(".data-table tr").filter({ hasText: sourceName }).last();
      page.once("dialog", (dialog) => dialog.accept());
      await row.getByRole("button", { name: "启用", exact: true }).click();
      await expect(card.getByText("自动更新已开启")).toBeVisible();
    }
    await card.getByRole("button", { name: "立即检查" }).click();
    const discoveryOutcome = await page.waitForFunction(() => {
      const error = document.querySelector<HTMLElement>(".inline-error");
      if (error?.offsetParent) return { state: "error", text: error.textContent?.trim() || "发现失败" };
      const status = document.querySelector<HTMLElement>('[role="status"]');
      if (status?.textContent?.includes("发现完成")) return { state: "done", text: status.textContent.trim() };
      return null;
    }, undefined, { timeout: 120_000 });
    const discoveryResult = await discoveryOutcome.jsonValue();
    expect(discoveryResult.state, discoveryResult.text).toBe("done");
    const controlled = page.locator(".controlled-crawl");
    await expect(controlled).toBeVisible();
    const candidates = controlled.locator("tbody tr");
    if (await candidates.count()) {
      await candidates.first().getByRole("button", { name: "影子采集" }).click();
      await expect(page.getByRole("status")).toContainText("影子采集完成", { timeout: 90_000 });
      await expect(page.locator(".shadow-preview")).toContainText("未落库");
    } else {
      await expect(controlled).toContainText("没有发现可验收的文章 URL");
    }
    await page.screenshot({ path: path.join(artifactRoot, "dachang-discovery-shadow.png"), fullPage: true });
  } finally {
    if (wasDisabled) {
      if (!await page.locator(".collection-advanced").isVisible().catch(() => false)) {
        await page.getByRole("button", { name: /高级管理/ }).click();
      }
      const row = page.locator(".data-table tr").filter({ hasText: sourceName }).last();
      const stop = row.getByRole("button", { name: "停用", exact: true });
      if (await stop.count()) {
        page.once("dialog", (dialog) => dialog.accept());
        await stop.click();
      }
    }
  }
});
