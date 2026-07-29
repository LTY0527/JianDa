import { expect, test, type Page, type Route } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

const document = {
  id: 922,
  title: "社区健康服务内容更新",
  raw_text: "这是 V2 网页正文。原有 V1 已发布，V2 尚待人工审核。",
  page_count: 1,
  category: "健康",
  organization_name: "简达平台",
  source_name: "权威健康来源",
  source_type: "WEB_ARTICLE",
  canonical_url: "https://health.example.gov.cn/article/922",
  source_authority_level: "A",
  original_published_at: "2026-07-29T09:00:00+08:00",
  cover_image_url: "https://images.example.gov.cn/article/922-cover.jpg",
  cover_image_type: "ORIGINAL_COVER",
  image_source_name: "",
  image_source_url: "https://health.example.gov.cn/article/922",
  image_alt_text: "社区健康服务现场",
  image_cached: false,
  image_license_note: "",
  image_reviewed: false,
  original_page_available: true,
  content_kind: "HEALTH_EDUCATION",
  version_root_id: 921,
  previous_version_id: 921,
  version_no: 2,
  content_change_summary: "正文新增一项健康服务安排。",
  processing_status: "WAITING_REVIEW",
};

const candidates = [
  {
    id: 801,
    document_id: 922,
    candidate_url: "https://images.example.gov.cn/article/922-cover.jpg",
    source_page_url: "https://health.example.gov.cn/article/922",
    source_name: "权威健康来源",
    alt_text: "社区健康服务现场",
    width: 1200,
    height: 675,
    mime_type: "image/jpeg",
    image_hash: "safe-hash-801",
    image_cached: false,
    discovery_method: "OPEN_GRAPH",
    priority_rank: 1,
    rights_status: "UNCONFIRMED",
    review_status: "PENDING",
  },
  {
    id: 802,
    document_id: 922,
    candidate_url: "https://images.example.gov.cn/article/922-body.jpg",
    source_page_url: "https://health.example.gov.cn/article/922",
    source_name: "权威健康来源",
    alt_text: "健康服务说明配图",
    width: 900,
    height: 600,
    mime_type: "image/jpeg",
    image_hash: "safe-hash-802",
    image_cached: false,
    discovery_method: "ARTICLE_IMAGE",
    priority_rank: 2,
    rights_status: "UNCONFIRMED",
    review_status: "PENDING",
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

function installMocks(
  page: Page,
  calls: { approved: number[]; rejected: number[]; defaults: number[] },
) {
  return page.route("**/*", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (url.hostname === "images.example.gov.cn") {
      return route.fulfill({
        status: 200,
        contentType: "image/svg+xml",
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="675"><rect width="1200" height="675" fill="#dcebe7"/></svg>',
      });
    }
    if (path === "/api/documents/922") return json(route, document);
    if (path === "/api/documents/922/fields") {
      return json(route, [{
        id: 51, field_label: "适用对象", field_value: "社区居民", page_no: 1,
        source_quote: "社区居民", confidence: 0.98, review_status: "CONFIRMED",
      }]);
    }
    if (path === "/api/documents/922/jobs") {
      return json(route, [{ id: 91, status: "SUCCEEDED", progress: 100 }]);
    }
    if (path === "/api/documents/922/generated") {
      return json(route, [
        { id: 61, content_type: "SUMMARY", content_json: '["更新了社区健康服务安排。"]', plain_text: "更新了社区健康服务安排。", status: "GENERATED" },
        { id: 62, content_type: "PLAIN_TEXT", plain_text: "请按新安排参加社区健康服务。", status: "GENERATED" },
      ]);
    }
    if (path === "/api/web-articles/922/image-candidates") return json(route, candidates);
    const approve = path.match(/^\/api\/web-articles\/image-candidates\/(\d+)\/approve$/);
    if (approve) {
      calls.approved.push(Number(approve[1]));
      return json(route, null);
    }
    const reject = path.match(/^\/api\/web-articles\/image-candidates\/(\d+)\/reject$/);
    if (reject) {
      calls.rejected.push(Number(reject[1]));
      return json(route, null);
    }
    if (path === "/api/web-articles/922/cover/category-default") {
      calls.defaults.push(922);
      return json(route, null);
    }
    if (path === "/api/documents/922/publish") {
      return json(route, null, 409, "图片尚未完成人工审核");
    }
    return route.continue();
  });
}

test("V2 审核保留已发布 V1，并完整展示和确认图片候选来源", async ({ page }) => {
  await prepare(page);
  const calls = { approved: [] as number[], rejected: [] as number[], defaults: [] as number[] };
  await installMocks(page, calls);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/documents/922/review`);

  await expect(page.getByText(/当前审核对象：V2/)).toBeVisible();
  await expect(page.getByText(/已发布的 V1 继续保持公开/)).toBeVisible();
  const firstCandidate = page.locator(".web-article-images figure").first();
  await expect(firstCandidate).toContainText(candidates[0].candidate_url);
  await expect(firstCandidate).toContainText(candidates[0].source_page_url);
  await expect(firstCandidate).toContainText("OPEN_GRAPH");
  await expect(firstCandidate).toContainText("1200×675");
  await expect(firstCandidate).toContainText("image/jpeg");
  await expect(firstCandidate).toContainText(candidates[0].alt_text);

  const approve = firstCandidate.getByRole("button", { name: "确认可用" });
  await expect(approve).toBeDisabled();
  await page.getByLabel("图片来源").fill("权威健康来源原网页");
  await page.getByLabel("许可说明").fill("人工核对来源页并记录公开使用依据");
  await expect(approve).toBeEnabled();
  await approve.click();
  await expect.poll(() => calls.approved).toEqual([801]);
});

test("拒绝候选后可改用分类默认图，未审核图片明确阻止发布", async ({ page }) => {
  await prepare(page);
  const calls = { approved: [] as number[], rejected: [] as number[], defaults: [] as number[] };
  await installMocks(page, calls);
  await page.goto(`${institutionUrl}/documents/922/review`);
  await page.getByLabel("拒绝原因").fill("版权范围不明确，改用平台默认图");
  await page.locator(".web-article-images figure").nth(1).getByRole("button", { name: "拒绝" }).click();
  await expect.poll(() => calls.rejected).toEqual([802]);
  await page.getByRole("button", { name: /使用分类默认图/ }).click();
  await expect.poll(() => calls.defaults).toEqual([922]);

  await page.goto(`${institutionUrl}/documents/922/publish`);
  await expect(page.getByText(/发布已阻止：网页文章图片尚未完成人工审核/)).toBeVisible();
  await expect(page.getByRole("button", { name: "审核通过并发布" })).toBeDisabled();
});

test("版本和图片审核在手机与桌面视口无明显横向溢出", async ({ page }) => {
  await prepare(page);
  const calls = { approved: [] as number[], rejected: [] as number[], defaults: [] as number[] };
  await installMocks(page, calls);
  for (const viewport of [
    { width: 375, height: 812 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto(`${institutionUrl}/documents/922/review`);
    await expect(page.getByText(/当前审核对象：V2/)).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBeTruthy();
  }
});
