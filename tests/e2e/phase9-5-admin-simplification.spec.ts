import { expect, test } from "@playwright/test";

const institutionUrl = process.env.JIANDA_INSTITUTION_TEST_URL ?? "http://127.0.0.1:5173";
const documents = [
  { id: 1, title: "待核对的社区活动", source_type: "PDF", file_name: "社区活动.pdf", organization_name: "大场镇社区服务中心", status: "WAITING_REVIEW", progress: 100, updated_at: "2026-08-23T08:00:00" },
  { id: 2, title: "可以发布的便民通知", source_type: "WEB_ARTICLE", source_name: "上海市宝山区人民政府", category: "社区服务", organization_name: "简达平台运营中心", status: "REVIEWED", progress: 100, updated_at: "2026-08-23T08:10:00" },
  { id: 3, title: "需要重新处理的材料", source_type: "IMAGE", file_name: "通知.jpg", organization_name: "大场镇社区服务中心", status: "FAILED", progress: 30, updated_at: "2026-08-23T08:20:00" },
  { id: 4, title: "已经公开的健康提醒", source_type: "WEB_ARTICLE", source_name: "上海市卫生健康委员会", category: "健康", organization_name: "简达平台运营中心", status: "PUBLISHED", progress: 100, updated_at: "2026-08-22T18:00:00" },
];

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "phase9-5-ui-test-token");
    localStorage.setItem("jianda_user_info", JSON.stringify({
      id: 1,
      organizationId: 1,
      username: "platform_admin",
      displayName: "王老师",
      role: "PLATFORM_ADMIN",
      organizationName: "简达平台运营中心",
    }));
  });
  await page.route("**/api/documents", (route) => route.fulfill({
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ success: true, data: documents }),
  }));
  await page.route("**/api/public-sources**", (route) => route.fulfill({
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ success: true, data: [] }),
  }));
});

test("管理员导航和工作台围绕今日待办收束", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(institutionUrl);
  const navigation = page.getByRole("navigation", { name: "主导航" });
  for (const label of ["工作台", "内容中心", "采集与来源", "商业运营", "数据概览", "系统记录"]) {
    await expect(navigation.getByRole("link", { name: label, exact: true })).toBeVisible();
  }
  await expect(navigation.getByRole("link")).toHaveCount(6);
  await expect(navigation).not.toContainText("材料管理");
  await expect(navigation).not.toContainText("公开信息导入");
  await expect(page.getByRole("heading", { name: "早上好，王老师" })).toBeVisible();
  await expect(page.getByText("今天有 3 件内容需要处理。" )).toBeVisible();
  await expect(page.getByRole("heading", { name: "今日待办" })).toBeVisible();
  await expect(page.locator(".today-todos li").first()).toContainText("需要关注");
  await expect(page.locator(".today-todos li").nth(1)).toContainText("待审核");
  await expect(page.locator(".today-todos li").nth(2)).toContainText("待发布");
});

test("内容中心合并状态并提供统一添加入口", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/documents`);
  await expect(page.getByRole("heading", { name: "内容中心" })).toBeVisible();
  const tabs = page.getByRole("navigation", { name: "内容状态" });
  for (const label of ["全部", "待处理", "待审核", "待发布", "已发布", "异常"]) {
    await expect(tabs.getByRole("button", { name: new RegExp(`^${label}`) })).toBeVisible();
  }
  await tabs.getByRole("button", { name: /^待审核/ }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect(page.locator("tbody tr")).toContainText("待核对的社区活动");

  await page.getByRole("button", { name: "添加内容" }).click();
  const dialog = page.getByRole("dialog", { name: "添加内容" });
  await expect(dialog.getByRole("link", { name: /上传 PDF \/ 图片/ })).toHaveAttribute("href", "/documents/upload");
  await expect(dialog.getByRole("link", { name: /粘贴网页链接/ })).toHaveAttribute("href", "/public-import?mode=web");
  await expect(dialog.getByRole("link", { name: /手工录入/ })).toHaveAttribute("href", "/public-import?mode=manual");

  await page.goto(`${institutionUrl}/public-import?mode=web`);
  await expect(page.getByRole("heading", { name: "添加内容" })).toBeVisible();
  await expect(page.getByRole("button", { name: "开发示例" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "粘贴网页链接" })).toBeVisible();
  await expect(page.getByRole("button", { name: "手工录入" })).toBeVisible();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});
