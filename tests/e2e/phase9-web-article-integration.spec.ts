import { expect, test, type Page } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";

const webDocument = {
  id: 27,
  title: "三伏天老年人健康提示",
  file_name: null,
  source_type: "WEB_ARTICLE",
  source_name: "新华网",
  original_published_at: "2026-07-26T08:00:00+08:00",
  category: "健康",
  content_kind: "HEALTH_EDUCATION",
  organization_name: "简达演示机构",
  status: "WAITING_REVIEW",
  progress: 100,
  updated_at: "2026-07-28T20:00:00+08:00",
};

async function authenticate(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "phase9-e2e-token");
    localStorage.setItem(
      "jianda_user_info",
      JSON.stringify({
        id: 2,
        organizationId: 2,
        username: "org_admin",
        displayName: "机构管理员",
        role: "ORG_ADMIN",
        organizationName: "简达演示机构",
      }),
    );
  });
}

function api(body: unknown) {
  return {
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: 0, message: "成功", data: body }),
  };
}

test.beforeEach(async ({ page }) => {
  await authenticate(page);
});

test("上传页可预览网页文章并使用真实文档 ID 进入处理页", async ({
  page,
}) => {
  await page.route("**/api/web-articles/preview", (route) =>
    route.fulfill(
      api({
        title: "三伏天老年人健康提示",
        source_name: "新华网",
        published_at: "2026-07-26T08:00:00+08:00",
        cover_image_type: "CATEGORY_DEFAULT",
        canonical_url: "https://www.news.cn/example/c.html",
        content_preview: "高温天气注意补水，身体不适时及时就医。",
        content_kind: "HEALTH_EDUCATION",
        authority_level: "A",
        robots_allowed: true,
        robots_status: "ALLOWED",
        warnings: ["未发现可安全使用的原图，将采用分类默认图"],
        image_cached: false,
      }),
    ),
  );
  await page.route("**/api/web-articles/import", (route) =>
    route.fulfill(api({ documentId: 27, imageReviewRequired: true })),
  );
  await page.route("**/api/documents/27/process", (route) =>
    route.fulfill(api({ documentId: 27, status: "WAITING_REVIEW" })),
  );
  await page.route("**/api/documents/27", (route) =>
    route.fulfill(
      api({
        ...webDocument,
        processing_status: "WAITING_REVIEW",
        raw_text: "高温天气注意补水，身体不适时及时就医。",
        original_html: "<article><p>高温天气注意补水。</p></article>",
        source_authority_level: "A",
      }),
    ),
  );
  await page.route("**/api/documents/27/fields", (route) =>
    route.fulfill(api([])),
  );
  await page.route("**/api/documents/27/generated", (route) =>
    route.fulfill(api([])),
  );
  await page.route("**/api/documents/27/segments", (route) =>
    route.fulfill(api([{ id: 1, page_no: 1, segment_no: 1, text: "高温天气注意补水。" }])),
  );
  await page.route("**/api/documents/27/jobs", (route) =>
    route.fulfill(
      api([{ id: 1, status: "SUCCEEDED", progress: 100, stage: "SUCCEEDED" }]),
    ),
  );

  await page.goto(`${institutionUrl}/documents/upload`);
  await page.getByRole("button", { name: "导入网页文章" }).click();
  await page
    .getByLabel("官方文章 URL")
    .fill("https://www.news.cn/example/c.html");
  await page.getByRole("button", { name: "识别网页内容" }).click();

  await expect(page.getByRole("heading", { name: "三伏天老年人健康提示" })).toBeVisible();
  await expect(page.getByText("新华网")).toBeVisible();
  await page.getByText("技术信息", { exact: true }).click();
  await expect(page.getByText("https://www.news.cn/example/c.html")).toBeVisible();
  await expect(page.getByText("抓取规则")).toBeVisible();
  await expect(page.getByText("ALLOWED")).toBeVisible();

  await page.getByRole("button", { name: "导入并开始处理" }).click();
  await expect(page).toHaveURL(
    `${institutionUrl}/documents/27/process?imported=web`,
  );
  await expect(page.getByText("网页文章已导入为文档 27")).toBeVisible();
});

test("工作台和材料列表将网页文章计入待审核", async ({ page }) => {
  await page.route("**/api/documents", (route) =>
    route.fulfill(api([webDocument])),
  );

  await page.goto(institutionUrl);
  await expect(page.getByText("等待审核").locator("..").getByText("1")).toBeVisible();
  await expect(
    page.getByRole("cell", { name: /三伏天老年人健康提示/ }),
  ).toBeVisible();
  await expect(page.getByText(/新华网.*健康.*2026年7月26日/)).toBeVisible();

  await page.goto(`${institutionUrl}/documents`);
  await expect(page.getByText("三伏天老年人健康提示")).toBeVisible();
  await expect(page.getByText("网页文章")).toBeVisible();
});

test("网页文章审核页读取快照且不会请求 PDF 原文件", async ({ page }) => {
  let originalFileRequests = 0;
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.route("**/api/documents/27/original-file", (route) => {
    originalFileRequests += 1;
    return route.fulfill({ status: 404, body: "" });
  });
  await page.route("https://images.example/missing.jpg", (route) =>
    route.fulfill({ status: 404, body: "" }),
  );
  await page.route("**/api/documents/27", (route) =>
    route.fulfill(
      api({
        ...webDocument,
        processing_status: "WAITING_REVIEW",
        raw_text: "三伏天里，老年人要主动补水。身体不适时及时就医。",
        original_html:
          '<article><p>三伏天里，老年人要主动补水。</p><img src="https://images.example/article.jpg" alt="健康提示配图"></article>',
        canonical_url: "https://www.news.cn/example/c.html",
        source_authority_level: "A",
        cover_image_url: "https://images.example/missing.jpg",
        cover_image_type: "ORIGINAL_COVER",
        image_source_name: "新华网",
        image_cached: false,
        image_reviewed: false,
        original_page_available: true,
      }),
    ),
  );
  await page.route("https://images.example/article.jpg", (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      path: path.resolve("apps/user-h5/public/images/defaults/health.svg"),
    }),
  );
  await page.route("**/api/documents/27/fields", (route) =>
    route.fulfill(
      api([
        {
          id: 2701,
          field_label: "适用对象",
          field_value: "老年人",
          page_no: 1,
          source_quote: "老年人要主动补水",
          confidence: 0.96,
          review_status: "PENDING",
        },
      ]),
    ),
  );
  await page.route("**/api/documents/27/jobs", (route) =>
    route.fulfill(
      api([{ id: 1, status: "SUCCEEDED", progress: 100, stage: "SUCCEEDED" }]),
    ),
  );
  await page.route("**/api/documents/27/generated", (route) =>
    route.fulfill(
      api([
        {
          id: 1,
          content_type: "SUMMARY",
          content_json: JSON.stringify(["天气炎热时要主动补水。"]),
          status: "GENERATED",
        },
        {
          id: 2,
          content_type: "PLAIN_TEXT",
          plain_text: "天气炎热时要主动补水，身体不适及时就医。",
          status: "GENERATED",
        },
      ]),
    ),
  );
  await page.route("**/api/web-articles/27/image-candidates", (route) =>
    route.fulfill(api([])),
  );

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/documents/27/review`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(page.getByText("三伏天里，老年人要主动补水。")).toBeVisible();
  await expect(page.getByText("天气炎热时要主动补水。")).toBeVisible();
  await expect(page.getByText("适用对象")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "查看官方原文" }),
  ).toHaveAttribute("href", "https://www.news.cn/example/c.html");
  await expect(page.locator(".web-source-review__cover img")).toHaveAttribute(
    "src",
    /\/images\/defaults\/health\.svg$/,
  );
  expect(originalFileRequests).toBe(0);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors).toEqual([
    "Failed to load resource: the server responded with a status of 404 (Not Found)",
  ]);
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-phase9-review-desktop.png"),
    fullPage: false,
  });
});
