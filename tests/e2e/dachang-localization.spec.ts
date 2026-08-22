import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1:5174";

const localItem = {
  id: 9101,
  slug: "dachang-open-month",
  title: "2026年大场镇政府开放月活动预告",
  summary: "大场镇居民可查看活动时间、地点和报名方式。",
  category: "社区服务",
  source_name: "上海市宝山区人民政府",
  published_at: "2026-08-22T09:00:00",
  content_kind: "SERVICE_NOTICE",
  region_code: "310113102",
  province: "上海市",
  city: "上海市",
  district: "宝山区",
  street_or_town: "大场镇",
  is_local: true,
};

async function mockItems(context: BrowserContext, items: unknown[]) {
  await context.route("**/api/public/items**", (route) => route.fulfill({
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: 0, message: "成功", data: items }),
  }));
}

async function expectNoOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
}

for (const viewport of [{ width: 375, height: 812 }, { width: 390, height: 844 }]) {
  test(`${viewport.width}px 首页显示大场镇并且地区选择器不溢出`, async ({ page, context }) => {
    await mockItems(context, [localItem]);
    await context.addInitScript(() => localStorage.setItem("jianda_font", "24"));
    await page.setViewportSize(viewport);
    await page.goto(h5Url);

    await expect(page.getByRole("heading", { name: /大场镇居民/ })).toBeVisible();
    await expect(page.getByText("2026年大场镇政府开放月活动预告")).toBeVisible();
    await expect(page.getByRole("link", { name: "邻里" })).toBeVisible();
    await expectNoOverflow(page);

    await page.getByRole("button", { name: "选择所在地区" }).click();
    await expect(page.getByRole("dialog", { name: "选择所在地区" })).toBeVisible();
    await expect(page.getByRole("button", { name: /黄浦区/ })).toBeDisabled();
    await expect(page.getByRole("button", { name: /友谊路街道/ })).toBeDisabled();
    await expect(page.getByRole("button", { name: /大场镇/ }).last()).toBeEnabled();
    await expect(page.getByText("当前仅开放上海市宝山区大场镇")).toBeVisible();
    await expectNoOverflow(page);
    await page.getByRole("button", { name: /大场镇/ }).last().click();
    await expect(page.getByRole("dialog", { name: "选择所在地区" })).toHaveCount(0);
  });
}

test("邻里空状态不使用演示内容冒充真实社区消息", async ({ page, context }) => {
  await mockItems(context, []);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`${h5Url}/neighborhood`);
  await expect(page.getByRole("heading", { name: "邻里" })).toBeVisible();
  await expect(page.getByText("上海市 · 宝山区 · 大场镇")).toBeVisible();
  await expect(page.getByText(/不会用演示内容冒充真实社区信息/)).toBeVisible();
  await expect(page.getByText("陈阿姨")).toHaveCount(0);
  await expectNoOverflow(page);
});
