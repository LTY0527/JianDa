import { expect, test } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const channels = ["推荐", "健康", "养老", "助餐", "办事", "防诈", "活动", "社区"];

test.describe("Phase 9.7 H5 首页无 Mock 真实验收", () => {
  test("频道可恢复且切换不重复请求首页数据", async ({ page }) => {
    let itemRequests = 0;
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.pathname === "/api/public/items") itemRequests += 1;
    });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/?channel=health`);
    await expect(page.getByRole("heading", { name: "健康内容" })).toBeVisible();
    await expect(page.getByRole("button", { name: "健康", exact: true })).toHaveAttribute("aria-current", "page");
    await expect.poll(() => itemRequests).toBe(1);

    for (const channel of channels) {
      await page.getByRole("button", { name: channel, exact: true }).click();
      await expect(page.getByRole("heading", { name: `${channel}内容` })).toBeVisible();
    }
    expect(itemRequests).toBe(1);
    await page.reload();
    await expect(page.getByRole("button", { name: "社区", exact: true })).toHaveAttribute("aria-current", "page");
  });

  test("真实图片可解码、频道条吸顶、搜索只带入问题且响应式无溢出", async ({ page }) => {
    for (const viewport of [
      { width: 375, height: 812 },
      { width: 390, height: 844 },
      { width: 768, height: 1024 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(viewport);
      await page.goto(h5Url);
      await expect(page.getByRole("navigation", { name: "首页频道" })).toHaveCSS("position", "sticky");
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
      const images = page.locator("main img:visible");
      for (let index = 0; index < await images.count(); index += 1) {
        await images.nth(index).scrollIntoViewIfNeeded();
        await expect.poll(() => images.nth(index).evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0 && image.naturalHeight > 0)).toBeTruthy();
      }
    }
    await page.getByRole("button", { name: "防诈", exact: true }).click();
    const ask = page.getByRole("link", { name: /问简达/ });
    if (await ask.count()) {
      await ask.click();
      await expect(page).toHaveURL(/\/assistant\?q=/);
      await expect(page.getByRole("textbox")).not.toHaveValue("");
      await expect(page.locator(".assistant-message--user")).toHaveCount(0);
    }
  });

  test("18/20/22/24px 阅读设置不会造成首页横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    for (const size of [18, 20, 22, 24]) {
      await page.addInitScript((font) => localStorage.setItem("jianda_font", String(font)), size);
      await page.goto(h5Url);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    }
  });
});
