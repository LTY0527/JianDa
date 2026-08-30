import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { authenticateResident } from "../support/residentAuth";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/phase9-9-3-final");

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

test("REAL 高德地图加载宝山区边界和三个已开通街镇", async ({ page }) => {
  test.setTimeout(60_000);
  const amapResponses: number[] = [];
  page.on("response", (response) => {
    if (/amap\.com|_AMapService/i.test(response.url())) {
      amapResponses.push(response.status());
    }
  });

  await authenticateResident(page, h5Url);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "选择所在地区" }).click();

  if (await page.getByText("地图服务尚未配置").isVisible()) {
    test.skip(true, "BLOCKED_BY_CONFIGURATION: 当前验收环境未配置高德 JS API 凭据");
  }

  const map = page.locator('.amap-region-map__canvas[data-boundary-ready="true"][data-marker-count="3"]');
  await expect(map).toBeVisible({ timeout: 20_000 });
  await expect(page.locator(".jianda-amap-label")).toHaveCount(3);
  await expect(page.locator(".jianda-amap-label", { hasText: "大场镇" })).toBeVisible();
  await expect(page.locator(".jianda-amap-label", { hasText: "顾村镇" })).toBeVisible();
  await expect(page.locator(".jianda-amap-label", { hasText: "庙行镇" })).toBeVisible();
  expect(amapResponses.length).toBeGreaterThan(0);
  expect(amapResponses.every((status) => status < 400)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);

  await page.screenshot({ path: path.join(artifactRoot, "h5-amap-baoshan-390.png"), fullPage: false });
  await page.locator('.amap-marker[title="顾村镇"]').click();
  await expect(page.getByRole("button", { name: "选择所在地区" })).toContainText("顾村镇");
  await page.screenshot({ path: path.join(artifactRoot, "h5-amap-gucun-selected-390.png"), fullPage: false });
});
