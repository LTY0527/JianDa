import { expect, test, type Page, type Route } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1:5174";
const items = [
  { id: 31, slug: "news-local", title: "大场镇健康服务提醒", summary: "大场镇居民可查看最新健康服务安排。", category: "健康", source_name: "宝山区权威来源", published_at: "2026-08-22T09:00:00+08:00", content_kind: "HEALTH_EDUCATION", is_local: true, region_code: "310113102", importance: 90 },
  { id: 32, slug: "guide-test", title: "大场镇社区事务办理通知", summary: "请在截止日期前办理。", category: "社区服务", source_name: "大场镇权威来源", source_url: "https://example.gov.cn/guide", published_at: "2026-08-21T09:00:00+08:00", content_kind: "SERVICE_NOTICE", is_local: true, region_code: "310113102", deadline_at: "2026-09-30T23:59:59+08:00", importance: 80 },
  { id: 33, slug: "news-fraud", title: "老年人防诈提醒", summary: "陌生来电先核实。", category: "防诈", source_name: "权威来源", published_at: "2026-08-20T09:00:00+08:00", content_kind: "ANTI_FRAUD", importance: 70 },
];
const detail = { ...items[1], raw_text: "办理地点：大场镇社区事务服务点。", generated: { SUMMARY: ["请在截止日期前办理。"], STEP_CARDS: [{ title: "核对材料", description: "查看官方要求。" }] }, fields: [
  { field_type: "TARGET_AUDIENCE", field_value: "大场镇居民" },
  { field_type: "END_DATE", field_value: "2026年8月30日" },
  { field_type: "LOCATION", field_value: "大场镇社区事务服务点" },
], original_file_available: false, verification_status: "VERIFIED" };
const directory = [{ id: 32, name: "大场镇社区事务办理通知", service_type: "社区服务", address: "大场镇社区事务服务点", description: "请在截止日期前办理。", source_url: "https://example.gov.cn/guide", source_name: "大场镇权威来源", last_verified_at: "2026-08-22T10:00:00+08:00" }];
const resident = { id: 1, username: "demo_chen", nickname: "陈阿姨", district: "宝山区", streetOrTown: "大场镇", regionCode: "310113102", demo: true };

async function json(route: Route, data: unknown) {
  await route.fulfill({ status: 200, contentType: "application/json; charset=utf-8", body: JSON.stringify({ code: 0, message: "成功", data }) });
}
async function mocks(page: Page, calls: string[]) {
  await page.addInitScript(() => localStorage.setItem("jianda_resident_token", "resident-token"));
  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (!url.pathname.startsWith("/api/")) return route.continue();
    if (url.pathname === "/api/public/items") return json(route, items);
    if (url.pathname === "/api/public/service-directory") return json(route, directory);
    if (url.pathname === "/api/public/items/guide-test") return json(route, detail);
    if (url.pathname === "/api/public/items/guide-test/neighbors") return json(route, { previous: null, next: null });
    if (url.pathname === "/api/public/items/32/view") return json(route, null);
    if (url.pathname === "/api/public/items/32/reminder") { calls.push(route.request().postData() || ""); return json(route, null); }
    if (url.pathname === "/api/public/reminders") return json(route, [{ id: 7, reminder_type: "DEADLINE", remind_at: "2026-09-30T01:00:00Z", published_item_id: 32, slug: "guide-test", title: "大场镇社区事务办理通知", category: "社区服务", content_kind: "SERVICE_NOTICE", content_status: "PUBLISHED" }]);
    if (url.pathname === "/api/public/resident/me") return json(route, resident);
    return json(route, null);
  });
}

test("首页形成强搜索、频道、单一 Hero、快捷任务和连续内容流", async ({ page }) => {
  await mocks(page, []);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(h5Url);
  await expect(page.getByRole("link", { name: "搜索通知、办事和社区服务" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "首页频道" })).toBeVisible();
  await expect(page.locator(".commercial-hero")).toHaveCount(1);
  await expect(page.getByRole("navigation", { name: "高频服务" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "推荐内容" })).toBeVisible();
  await page.getByRole("button", { name: "防诈", exact: true }).click();
  await expect(page).toHaveURL(/channel=fraud/);
  await expect(page.getByRole("heading", { name: "防诈内容" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});

test("服务目录只显示真实字段，缺失电话不使用兜底", async ({ page }) => {
  await mocks(page, []);
  await page.goto(`${h5Url}/services`);
  await expect(page.getByRole("heading", { name: "长辈常用服务" })).toBeVisible();
  await expect(page.getByText("大场镇社区事务服务点").first()).toBeVisible();
  await expect(page.getByText("长期有效")).toHaveCount(0);
  await expect(page.getByText("以最新通知为准")).toHaveCount(0);
  await expect(page.getByRole("link", { name: /查看官方来源/ })).toHaveAttribute("href", "https://example.gov.cn/guide");
});

test("详情提醒写入当前游客并可在我的提醒查看", async ({ page }) => {
  const calls: string[] = [];
  await mocks(page, calls);
  await page.goto(`${h5Url}/guide/guide-test`);
  await page.getByRole("button", { name: "提醒我" }).click();
  await expect(page.getByText(/提醒已保存/)).toBeVisible();
  await expect.poll(() => calls.length).toBe(1);
  expect(JSON.parse(calls[0])).toMatchObject({ reminderType: "DEADLINE" });
  await page.goto(`${h5Url}/reminders`);
  await expect(page.getByRole("heading", { name: "我的提醒" })).toBeVisible();
  await expect(page.getByText("大场镇社区事务办理通知")).toBeVisible();
});

test("个人中心显示服务端真实提醒数", async ({ page }) => {
  await mocks(page, []);
  await page.goto(`${h5Url}/profile`);
  await expect(page.locator(".profile-stats > div").filter({ hasText: "提醒" }).locator("b")).toHaveText("1");
});
