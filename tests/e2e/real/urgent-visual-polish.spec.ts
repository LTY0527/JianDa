import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { authenticateResident } from "../support/residentAuth";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/urgent-visual-polish-20260831");
const viewport = { width: 390, height: 844 };
const green = "rgb(14, 90, 85)";

test.describe.configure({ mode: "serial" });
test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

function recordRuntimeErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  return errors;
}

async function expectNoHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() =>
    document.documentElement.scrollWidth <= window.innerWidth,
  )).toBe(true);
}

test("REAL 390x844 居民首页使用统一墨绿体系且无异常留白", async ({ page }) => {
  const runtimeErrors = recordRuntimeErrors(page);
  await authenticateResident(page, h5Url);
  await page.setViewportSize(viewport);
  await page.goto(`${h5Url}/?region=310113102`, { waitUntil: "networkidle" });

  const search = page.locator(".home-search");
  const channels = page.locator(".home-channels");
  const hero = page.locator(".commercial-hero");
  const heroImage = hero.locator(":scope > img");
  await expect(search).toBeVisible();
  await expect(channels).toBeVisible();
  await expect(hero).toBeVisible();
  await expect(heroImage).toBeVisible();
  await expect.poll(() => heroImage.evaluate((image: HTMLImageElement) =>
    image.complete && image.naturalWidth > 0 && image.naturalHeight > 0,
  )).toBe(true);

  const heroSource = await heroImage.getAttribute("src");
  expect(heroSource).toBeTruthy();
  expect(heroSource).not.toContain("/images/defaults/");
  expect(await page.locator(".home-search > span").evaluate((node) =>
    getComputedStyle(node).whiteSpace,
  )).toBe("nowrap");

  const gaps = await page.evaluate(() => {
    const searchBox = document.querySelector(".home-search")!.getBoundingClientRect();
    const channelBox = document.querySelector(".home-channels")!.getBoundingClientRect();
    const heroBox = document.querySelector(".commercial-hero")!.getBoundingClientRect();
    return {
      searchToChannels: channelBox.top - searchBox.bottom,
      channelsToHero: heroBox.top - channelBox.bottom,
      heroTop: heroBox.top,
    };
  });
  expect(gaps.searchToChannels).toBeLessThanOrEqual(30);
  expect(gaps.channelsToHero).toBeLessThanOrEqual(20);
  expect(gaps.heroTop).toBeLessThan(viewport.height);

  const activeChannel = channels.locator("button.active");
  const inactiveChannel = channels.locator("button:not(.active)").first();
  expect(await activeChannel.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(green);
  expect(await inactiveChannel.evaluate((node) => getComputedStyle(node).color)).toBe("rgb(49, 90, 85)");

  const headerControls = page.locator(".h5-header button, .h5-header a");
  await expect(headerControls.first()).toBeVisible();
  const brandMark = page.locator(".h5-brand > span");
  expect(await brandMark.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(green);
  expect(await brandMark.locator("svg").evaluate((node) => getComputedStyle(node).color)).toBe("rgb(255, 255, 255)");
  const assistantBubble = page.locator(".bottom-nav__primary .bottom-nav__icon");
  const bubbleBox = await assistantBubble.boundingBox();
  expect(bubbleBox?.width).toBeGreaterThanOrEqual(58);
  expect(bubbleBox?.width).toBeLessThanOrEqual(62);
  expect(await assistantBubble.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(green);
  const serviceVisibility = await page.evaluate(() => {
    const services = document.querySelector(".quick-tasks")!.getBoundingClientRect();
    const bottomNav = document.querySelector(".bottom-nav")!.getBoundingClientRect();
    return bottomNav.top - services.top;
  });
  expect(serviceVisibility).toBeGreaterThanOrEqual(44);
  await expect(page.locator("body")).not.toContainText(/\?{4,}|？{4,}/);
  await expectNoHorizontalOverflow(page);

  await page.screenshot({ path: path.join(artifactRoot, "01_home_390x844_final_green.png"), fullPage: false });
  await page.screenshot({ path: path.join(artifactRoot, "02_home_top_no_blank_space.png"), fullPage: false });
  await page.evaluate(() => {
    const target = document.querySelector(".commercial-hero") as HTMLElement;
    window.scrollTo(0, Math.max(0, target.offsetTop - 72));
  });
  await page.screenshot({ path: path.join(artifactRoot, "03_home_real_hero.png"), fullPage: false });

  await page.evaluate(() => window.scrollTo(0, 0));
  await channels.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(artifactRoot, "04_home_channels_green.png"), fullPage: false });
  await page.locator(".quick-tasks").scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(artifactRoot, "05_home_assistant_bubble.png"), fullPage: false });
  expect(runtimeErrors).toEqual([]);
});

test("REAL 登录页品牌、Tab、按钮和焦点环统一为墨绿", async ({ page }) => {
  const runtimeErrors = recordRuntimeErrors(page);
  await page.setViewportSize(viewport);
  await page.goto(h5Url, { waitUntil: "domcontentloaded" });
  await page.evaluate(() => {
    localStorage.removeItem("jianda_resident_token");
    localStorage.removeItem("jianda_resident_profile");
  });
  await page.goto(`${h5Url}/resident/login`, { waitUntil: "networkidle" });
  await expect(page).toHaveURL(/\/resident\/login/);

  const logo = page.locator(".login-logo");
  const submit = page.locator(".login-submit");
  await expect(logo.locator("svg")).toBeVisible();
  expect(await logo.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(green);
  expect(await submit.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(green);
  expect(await page.locator(".login-tab.is-active").evaluate((node) =>
    getComputedStyle(node).color,
  )).toBe(green);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(artifactRoot, "06_login_390x844_green.png"), fullPage: false });

  await page.getByRole("button", { name: "用户名登录", exact: true }).click();
  const usernameTab = page.getByRole("button", { name: "用户名登录", exact: true });
  await expect(usernameTab).toHaveClass(/is-active/);
  const usernameInput = page.getByLabel("用户名");
  await page.keyboard.press("Tab");
  await expect(usernameInput).toBeFocused();
  await expect.poll(() => usernameInput.evaluate((node) =>
    getComputedStyle(node).borderColor,
  )).toBe(green);
  const focusStyle = await usernameInput.evaluate((node) => ({
    border: getComputedStyle(node).borderColor,
    shadow: getComputedStyle(node).boxShadow,
  }));
  expect(focusStyle.border).toBe(green);
  expect(focusStyle.shadow).toContain("rgba(14, 90, 85");
  await page.screenshot({ path: path.join(artifactRoot, "07_login_username_tab_green.png"), fullPage: false });
  expect(runtimeErrors).toEqual([]);
});

test("REAL 设置页主要操作延续墨绿体系", async ({ page }) => {
  const runtimeErrors = recordRuntimeErrors(page);
  await authenticateResident(page, h5Url);
  await page.setViewportSize(viewport);
  await page.goto(`${h5Url}/settings`, { waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: "阅读与内容偏好" })).toBeVisible();
  const activeControl = page.locator(".font-options button.active");
  await expect(activeControl).toBeVisible();
  expect(await activeControl.evaluate((node) => getComputedStyle(node).borderColor)).toBe(green);
  expect(await activeControl.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(green);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(artifactRoot, "08_search_or_settings_green_consistency.png"), fullPage: false });
  expect(runtimeErrors).toEqual([]);
});
