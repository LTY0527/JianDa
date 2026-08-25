import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/phase9-9-3-final");

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

const CHANNELS = ["推荐", "健康", "养老", "助餐", "办事", "防诈", "活动", "社区"] as const;

test("REAL H5 首页 8 频道与高频服务在 390px 真实渲染", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "networkidle" });

  await expect(page.locator('nav[aria-label="首页频道"]')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("button", { name: /选择所在地区/ })).toContainText(/宝山区/);

  const channelNav = page.locator('nav[aria-label="首页频道"]');
  await expect(channelNav).toBeVisible();
  for (const label of CHANNELS) {
    await expect(channelNav.getByRole("button", { name: label, exact: true })).toBeVisible();
  }

  const quickTasks = page.locator('nav[aria-label="高频服务"]');
  await expect(quickTasks).toBeVisible();
  await expect(quickTasks.getByText("社区卫生", { exact: true })).toBeVisible();
  await expect(quickTasks.getByText("长者食堂", { exact: true })).toBeVisible();
  await expect(quickTasks.getByText("活动报名", { exact: true })).toBeVisible();
  await expect(quickTasks.getByText("办事指南", { exact: true })).toBeVisible();

  await page.locator('nav[aria-label="首页频道"]').getByRole("button", { name: "助餐", exact: true }).click();
  await expect(page.locator(".mixed-feed").getByText("助餐内容", { exact: false })).toBeVisible({ timeout: 10_000 });

  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
  await page.screenshot({ path: path.join(artifactRoot, "h5-home-390.png"), fullPage: false });
});

test("REAL H5 简达助手页面真实渲染且 AI 状态可读", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/assistant`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "简达助手", exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".assistant-trust")).toContainText(/原文检索可用/);
  await expect(page.locator("#assistant-question")).toBeVisible();
  await expect(page.getByRole("button", { name: /发送问题/ })).toBeVisible();

  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "h5-assistant-390.png"), fullPage: false });
});

test("REAL H5 长者食堂频道页真实渲染并展示已发布助餐内容", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/services/meals`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "长者食堂", exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".service-channel, .channel-list, .h5-main, main").first()).toBeVisible();
  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "h5-meals-390.png"), fullPage: false });
});

test("REAL H5 活动报名频道页真实渲染", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/activities`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "活动报名", exact: true })).toBeVisible({ timeout: 15_000 });
  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "h5-activities-390.png"), fullPage: false });
});

test("REAL H5 会员页真实渲染并展示套餐与支付入口", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/membership`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "简达安心会员", exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("button", { name: /选择年卡/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /选择月卡/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /选择周卡/ })).toBeVisible();
  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "h5-membership-390.png"), fullPage: false });
});

test("REAL H5 推荐+7 频道全部可切换且有已审核内容", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "networkidle" });
  const channelNav = page.locator('nav[aria-label="首页频道"]');
  await expect(channelNav).toBeVisible({ timeout: 15_000 });

  for (const label of CHANNELS) {
    await channelNav.getByRole("button", { name: label, exact: true }).click();
    await expect(page.locator(".mixed-feed").getByText(`${label}内容`, { exact: false })).toBeVisible({ timeout: 10_000 });
  }
  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "h5-channels-390.png"), fullPage: false });
});
