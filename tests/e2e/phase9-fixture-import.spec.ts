import { expect, test, type Page, type Route } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const fixturesEnabled = process.env.JIANDA_ENABLE_DEV_FIXTURES === "true";

const fixtures = [
  {
    fixtureId: "anti-fraud-elderly-2026",
    title: "警惕冒充客服退款诈骗，守好养老钱",
    sourceName: "国家反诈中心", sourceType: "GOVERNMENT",
    sourceUrl: "https://official.example/anti-fraud", publisher: "国家反诈中心",
    publishedAt: "2026-06-18", category: "反诈", body: "反诈测试正文。",
  },
  {
    fixtureId: "hypertension-daily-care-2026",
    title: "高血压患者夏季日常管理提示",
    sourceName: "城市人民医院", sourceType: "HOSPITAL",
    sourceUrl: "https://official.example/health", publisher: "城市人民医院",
    publishedAt: "2026-07-20", category: "健康", body: "健康测试正文。",
  },
  {
    fixtureId: "community-elderly-service-2026",
    title: "浦江街道社区养老服务站夏季服务安排",
    sourceName: "浦江街道办事处", sourceType: "PUBLIC_INSTITUTION",
    sourceUrl: "https://official.example/community", publisher: "浦江街道办事处",
    publishedAt: "2026-07-15", category: "养老", body: "社区服务测试正文。",
  },
];

async function json(route: Route, data: unknown, status = 200, message = "成功") {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: status === 200 ? 0 : status, message, data }),
  });
}

async function prepare(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "isolated-platform-token");
    localStorage.setItem("jianda_user_info", JSON.stringify({
      id: 1, organizationId: 1, username: "platform_admin",
      displayName: "平台管理员", role: "PLATFORM_ADMIN", organizationName: "简达平台",
    }));
  });
}

test("三个稳定 fixture 可按 ID 导入并刷新导入记录", async ({ page }) => {
  test.skip(!fixturesEnabled, "生产构建默认隐藏开发 fixture；仅显式启用时验收该入口");
  await prepare(page);
  const importedIds: string[] = [];
  let importListReads = 0;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/public-sources") return json(route, []);
    if (path === "/api/public-sources/fixtures") return json(route, fixtures);
    if (path === "/api/public-sources/imports") {
      importListReads += 1;
      return json(route, importedIds.length ? [{
        id: 801, title: fixtures[1].title, status: "UPLOADED", category: "健康",
        import_method: "FIXTURE", import_url: fixtures[1].sourceUrl,
        source_published_at: fixtures[1].publishedAt, imported_at: "2026-07-29T12:00:00",
        source_name: fixtures[1].sourceName,
      }] : []);
    }
    const match = path.match(/^\/api\/public-sources\/import\/fixture\/(.+)$/);
    if (match) {
      importedIds.push(decodeURIComponent(match[1]));
      return json(route, { documentId: 801 });
    }
    return json(route, null, 404, "测试未配置该接口");
  });

  await page.goto(`${institutionUrl}/public-import`);
  await page.getByRole("button", { name: "本地示例导入" }).click();
  for (const fixture of fixtures) {
    await expect(page.getByRole("heading", { name: fixture.title })).toBeVisible();
  }
  const target = page.locator(".fixture-list article").filter({ hasText: fixtures[1].title });
  await target.getByRole("button", { name: "导入" }).click();
  await expect.poll(() => importedIds).toEqual(["hypertension-daily-care-2026"]);
  await expect.poll(() => importListReads).toBeGreaterThan(1);
  await expect(page.getByText(new RegExp(`“${fixtures[1].title}”已导入`))).toBeVisible();
  await expect(page.locator(".import-history")).toContainText(fixtures[1].title);
});

test("fixture API 失败显示明确错误而不是伪装为空列表", async ({ page }) => {
  test.skip(!fixturesEnabled, "生产构建默认隐藏开发 fixture；仅显式启用时验收该入口");
  await prepare(page);
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/public-sources") return json(route, []);
    if (path === "/api/public-sources/fixtures") {
      return json(route, null, 503, "本地示例暂时无法读取，请稍后重试。");
    }
    if (path === "/api/public-sources/imports") return json(route, []);
    return json(route, null, 404, "测试未配置该接口");
  });
  await page.goto(`${institutionUrl}/public-import`);
  await expect(page.getByText("本地示例暂时无法读取，请稍后重试。")).toBeVisible();
  await page.getByRole("button", { name: "本地示例导入" }).click();
  await expect(page.getByText("暂无导入记录。")).toBeVisible();
  await expect(page.locator(".fixture-list")).not.toBeVisible();
});
