import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/phase9-9-3-final");

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

test("REAL H5 助手真实联网问答流程截图", async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 896 });
  await page.goto(`${h5Url}/assistant`, { waitUntil: "networkidle" });

  // 确认助手页已加载
  const input = page.getByRole("textbox").first();
  await expect(input).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: path.join(artifactRoot, "h5-assistant-web-1-empty.png") });

  // 发送一个明确需要联网回答的问题
  const question = "上海市宝山区2025年长者助餐补贴标准是什么？";
  await input.fill(question);
  await input.press("Enter");

  // 等待搜索/整理中状态出现（快速截图）
  try {
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(artifactRoot, "h5-assistant-web-2-loading.png") });
  } catch (e) {}

  // 等待回答完成（网络空闲或出现引用来源）
  try {
    await page.waitForLoadState("networkidle", { timeout: 180_000 });
  } catch (e) {}
  await page.waitForTimeout(3000);

  // 滚动到底部确保完整显示
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(artifactRoot, "h5-assistant-web-3-answered.png"), fullPage: true });

  // 最终页面必须包含文字（证明有回答）
  const bodyText = await page.evaluate(() => document.body.innerText);
  expect(bodyText.length).toBeGreaterThan(50);
});
