import { expect, test, type Page, type Request } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { authenticateResident } from "../support/residentAuth";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/urgent-h5-final-polish-20260831");
const viewport = { width: 390, height: 844 };
const contextSlug = "social-security-card-renewal";
const contextTitle = "社会保障卡到期换领指南";

test.describe.configure({ mode: "serial" });
test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

async function prepareResident(page: Page) {
  await authenticateResident(page, h5Url);
  await page.setViewportSize(viewport);
}

async function expectNoHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() =>
    document.documentElement.scrollWidth <= window.innerWidth,
  )).toBe(true);
}

async function expectNoPureBluePrimaryAction(page: Page) {
  const offending = await page.locator("a:visible, button:visible").evaluateAll((nodes) =>
    nodes
      .filter((node) => {
        const style = getComputedStyle(node);
        return [style.backgroundColor, style.color, style.borderColor]
          .some((color) => ["rgb(0, 87, 184)", "rgb(11, 99, 200)", "rgb(0, 102, 204)"].includes(color));
      })
      .map((node) => (node.textContent || node.getAttribute("aria-label") || node.tagName).trim()),
  );
  expect(offending).toEqual([]);
}

function assistantRequests(page: Page) {
  const requests: Array<Record<string, unknown>> = [];
  page.on("request", (request: Request) => {
    if (!request.url().includes("/api/public/assistant/chat") || request.method() !== "POST") return;
    requests.push(request.postDataJSON() as Record<string, unknown>);
  });
  return requests;
}

test("REAL 390x844 白色青绿首页、统一品牌和底栏位置", async ({ page }) => {
  await prepareResident(page);
  await page.goto(`${h5Url}/?region=310113102`, { waitUntil: "networkidle" });

  expect(await page.locator("body").evaluate((node) => getComputedStyle(node).backgroundColor)).toBe("rgb(255, 255, 255)");
  await expectNoPureBluePrimaryAction(page);
  await expectNoHorizontalOverflow(page);

  const firstFeedBody = page.locator(".mixed-feed .feed-entry__body").first();
  await expect(firstFeedBody).toBeVisible();
  const feedBox = await firstFeedBody.boundingBox();
  expect(feedBox?.x).toBeGreaterThanOrEqual(18);

  const assistantLabel = page.locator(".bottom-nav__primary > span:last-child");
  await expect(assistantLabel).toBeVisible();
  const labelBox = await assistantLabel.boundingBox();
  expect(labelBox).not.toBeNull();
  expect(labelBox!.y + labelBox!.height).toBeLessThan(viewport.height - 2);
  expect(viewport.height - (labelBox!.y + labelBox!.height)).toBeGreaterThanOrEqual(4);

  await page.screenshot({ path: path.join(artifactRoot, "01_home_final_white_teal_390x844.png") });
  await firstFeedBody.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(artifactRoot, "02_home_feed_left_padding.png") });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(artifactRoot, "04_bottom_assistant_label_fixed.png") });

});

test("REAL 登录页复用首页品牌图标并保持白色背景", async ({ page }) => {
  await page.setViewportSize(viewport);
  await page.addInitScript(() => {
    localStorage.removeItem("jianda_resident_token");
    localStorage.removeItem("jianda_resident_profile");
  });
  await page.goto(`${h5Url}/resident/login`, { waitUntil: "networkidle" });
  expect(await page.locator("body").evaluate((node) => getComputedStyle(node).backgroundColor)).toBe("rgb(255, 255, 255)");
  const loginBrandSvg = await page.locator(".login-logo svg").evaluate((node) => node.outerHTML.replace(/\s+/g, " "));
  expect(loginBrandSvg).toContain("lucide-heart-handshake");
  expect(await page.locator(".login-logo path").first().evaluate((node) => getComputedStyle(node).fill)).toBe("none");
  await expectNoPureBluePrimaryAction(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(artifactRoot, "03_login_brand_logo_white_bg.png") });
});

test("REAL 390x844 我的页面压缩和服务搜索框对齐", async ({ page }) => {
  await prepareResident(page);
  await page.goto(`${h5Url}/profile`, { waitUntil: "networkidle" });

  const stats = page.locator(".profile-stats");
  await expect(stats).toBeVisible();
  expect(await stats.locator(":scope > div").count()).toBe(4);
  const statsBox = await stats.boundingBox();
  expect(statsBox?.height).toBeGreaterThanOrEqual(90);
  expect(statsBox?.height).toBeLessThanOrEqual(110);
  const menuHeights = await page.locator(".profile-links > a").evaluateAll((nodes) =>
    nodes.map((node) => node.getBoundingClientRect().height),
  );
  expect(menuHeights.length).toBeGreaterThan(0);
  for (const height of menuHeights) {
    expect(height).toBeGreaterThanOrEqual(60);
    expect(height).toBeLessThanOrEqual(68);
  }
  await expectNoPureBluePrimaryAction(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(artifactRoot, "05_profile_compact.png") });

  await page.goto(`${h5Url}/services?region=310113102`, { waitUntil: "networkidle" });
  const search = page.locator(".search-input").first();
  const searchIcon = search.locator("svg");
  const searchInput = search.locator("input");
  await expect(searchInput).toBeVisible();
  const centers = await Promise.all([searchIcon, searchInput].map(async (locator) => {
    const box = await locator.boundingBox();
    return box!.y + box!.height / 2;
  }));
  expect(Math.abs(centers[0] - centers[1])).toBeLessThanOrEqual(2);
  expect(await searchInput.evaluate((node) => getComputedStyle(node).borderWidth)).toBe("0px");
  await searchInput.focus();
  await expect.poll(() => search.evaluate((node) => getComputedStyle(node).borderColor)).toBe("rgb(47, 119, 113)");
  await expect.poll(() => search.evaluate((node) => getComputedStyle(node).boxShadow)).toContain("rgba(47, 119, 113");
  await expectNoPureBluePrimaryAction(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(artifactRoot, "06_services_search_aligned.png") });
});

test("REAL 详情进入帖子上下文问答、引用当前材料并退出", async ({ page }) => {
  test.setTimeout(120_000);
  await prepareResident(page);
  const requests = assistantRequests(page);

  await page.goto(`${h5Url}/guide/${contextSlug}`, { waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: contextTitle })).toBeVisible();
  const askThisItem = page.getByRole("link", { name: /询问这个事项/ });
  await askThisItem.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(artifactRoot, "07_detail_ask_this_item.png") });
  await askThisItem.click();

  await expect(page).toHaveURL(new RegExp(`/assistant\\?mode=context&slug=${contextSlug}`));
  const contextCard = page.locator(".chat-context");
  await expect(contextCard).toContainText("正在基于这篇内容提问");
  await expect(contextCard).toContainText(contextTitle);
  const composer = page.locator(".assistant-composer-new");
  const composerBox = await composer.boundingBox();
  expect(composerBox).not.toBeNull();
  expect(composerBox!.y).toBeLessThan(viewport.height);
  expect(composerBox!.y + Math.min(composerBox!.height, 80)).toBeGreaterThan(0);
  const input = composer.locator("textarea");
  await expect(input).toHaveAttribute("placeholder", "继续问这篇内容，例如：需要准备什么材料？");
  await expectNoPureBluePrimaryAction(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(artifactRoot, "08_assistant_context_mode_bottom.png") });

  await input.fill("这项服务需要准备什么？");
  await composer.getByRole("button", { name: "发送" }).click();
  await expect.poll(() => requests.length, { timeout: 15_000 }).toBe(1);
  expect(requests[0].contextSlug).toBe(contextSlug);
  await expect(page.locator(".chat-msg--assistant").last()).toBeVisible({ timeout: 90_000 });
  await expect(page.locator(".chat-msg--assistant").last()).toContainText(/身份证|社会保障卡/, { timeout: 90_000 });
  const citations = page.locator(".chat-citations").last();
  await expect(citations).toBeVisible({ timeout: 90_000 });
  await citations.locator("summary").click();
  await expect(citations).toContainText(contextTitle);
  await page.waitForTimeout(500);
  await page.evaluate(() => window.scrollBy({ top: 240, behavior: "auto" }));
  await page.screenshot({ path: path.join(artifactRoot, "09_assistant_context_answer_citation.png") });

  await contextCard.getByRole("button", { name: "退出此事项" }).click();
  await expect(contextCard).toHaveCount(0);
  await expect(input).toHaveAttribute("placeholder", "输入问题，例如：最近有哪些健康提醒？");
  await input.fill("简达助手运行正常吗？");
  await composer.getByRole("button", { name: "发送" }).click();
  await expect.poll(() => requests.length, { timeout: 15_000 }).toBe(2);
  expect(requests[1].contextSlug).toBeUndefined();
  await expect(page.locator(".chat-msg--assistant").last()).toBeVisible({ timeout: 30_000 });
  await page.screenshot({ path: path.join(artifactRoot, "10_assistant_normal_mode.png") });
});

test("REAL 不可见上下文明确降级且不发送回答请求", async ({ page }) => {
  await prepareResident(page);
  const requests = assistantRequests(page);
  await page.goto(`${h5Url}/assistant?mode=context&slug=not-visible-context`, { waitUntil: "networkidle" });
  await expect(page.getByRole("alert")).toContainText("该内容当前不可用于提问");
  await expect(page.getByRole("button", { name: "切换为普通提问" })).toBeVisible();
  await expect(page.locator(".chat-input__send")).toBeDisabled();
  expect(requests).toEqual([]);
  await expectNoHorizontalOverflow(page);
});
