import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve(
  process.env.JIANDA_FINAL_ACCEPTANCE_ARTIFACT_DIR
    ?? "artifacts/phase9-9-3-final",
);
const residentUsername = process.env.REAL_RESIDENT_USERNAME ?? "demo_chen";
const residentPassword = process.env.REAL_RESIDENT_PASSWORD ?? "Resident@123";

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

test.beforeEach(async ({ page }) => {
  const response = await page.request.post(`${h5Url}/api/public/resident/login`, {
    data: { username: residentUsername, password: residentPassword },
  });
  expect(response.ok()).toBeTruthy();
  const payload = (await response.json()).data as { token: string; profile: unknown };
  await page.addInitScript((session) => {
    localStorage.setItem("jianda_resident_token", session.token);
    localStorage.setItem("jianda_resident_profile", JSON.stringify(session.profile));
  }, payload);
});

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
  await expect(page.locator(".chat-welcome__trust")).toContainText(/原文核对/);
  await expect(page.locator(".chat-welcome__trust")).toContainText(/AI 不可用时使用确定性检索/);
  await expect(page.getByPlaceholder(/输入问题/)).toBeVisible();
  await expect(page.getByRole("button", { name: "发送", exact: true })).toBeVisible();

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

test("REAL H5 全功能路由、字号、图片与触控热区重新统计", async ({ page }) => {
  test.setTimeout(180_000);
  await page.setViewportSize({ width: 390, height: 844 });
  const routes = [
    "/", "/news", "/services", "/services/health", "/services/meals",
    "/services/contacts", "/services/guides", "/activities", "/trusted-services",
    "/favorites", "/history", "/reminders", "/profile", "/settings",
    "/listen", "/neighborhood", "/assistant", "/assistant/history", "/orders", "/membership",
  ];
  const controls: Array<Record<string, unknown>> = [];
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && !/favicon|404/i.test(message.text())) consoleErrors.push(message.text());
  });

  for (const route of routes) {
    await page.goto(`${h5Url}${route}`, { waitUntil: "networkidle" });
    await expect(page.locator("body")).not.toHaveText("");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), route).toBe(true);
    const pageControls = await page.evaluate(() => Array.from(document.querySelectorAll<HTMLElement>(
      'a[href],button,input:not([type="hidden"]),select,textarea,[role="button"]',
    )).filter((element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.visibility !== "hidden" && style.display !== "none" && rect.width > 0 && rect.height > 0;
    }).map((element, index) => {
      const rect = element.getBoundingClientRect();
      return {
        index,
        tag: element.tagName.toLowerCase(),
        text: (element.innerText || element.getAttribute("aria-label") || element.getAttribute("placeholder") || "").trim().slice(0, 60),
        className: element.className?.toString().slice(0, 100) || "",
        width: Math.round(rect.width * 10) / 10,
        height: Math.round(rect.height * 10) / 10,
      };
    }));
    controls.push(...pageControls.map((control) => ({ route, ...control })));
  }

  for (const size of [18, 20, 22, 24]) {
    await page.evaluate((fontSize) => localStorage.setItem("jianda_font", String(fontSize)), size);
    await page.reload({ waitUntil: "networkidle" });
    const actual = await page.evaluate(() => getComputedStyle(document.documentElement)
      .getPropertyValue("--reader-size").trim());
    expect(actual).toBe(`${size}px`);
  }

  await page.goto(`${h5Url}/search?q=${encodeURIComponent("不存在的验收关键词9Z8Y7X")}`, { waitUntil: "networkidle" });
  await expect(page.getByRole("link", { name: /带关键词问简达/ })).toBeVisible();

  await page.goto(`${h5Url}/news`, { waitUntil: "networkidle" });
  const imageFailures = await page.evaluate(() => Array.from(document.images)
    .filter((image) => image.getBoundingClientRect().width > 0)
    .filter((image) => image.naturalWidth <= 0 || image.naturalHeight <= 0)
    .map((image) => image.currentSrc || image.src));
  expect(imageFailures).toEqual([]);

  const undersized = controls.filter((control) => Number(control.width) < 44 || Number(control.height) < 44);
  fs.writeFileSync(path.join(artifactRoot, "h5-touch-targets.json"), JSON.stringify({
    viewport: "390x844",
    routeCount: routes.length,
    total: controls.length,
    compliant: controls.length - undersized.length,
    complianceRate: controls.length ? Number(((controls.length - undersized.length) / controls.length * 100).toFixed(2)) : 0,
    undersized,
    consoleErrors,
  }, null, 2));
  expect(consoleErrors).toEqual([]);
  await page.screenshot({ path: path.join(artifactRoot, "h5-final-news-390.png"), fullPage: false });
});
