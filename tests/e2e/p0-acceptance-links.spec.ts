import { expect, test, type Page, type Route } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

const orgUser = {
  id: 2,
  organizationId: 2,
  username: "org_admin",
  displayName: "机构管理员",
  role: "ORG_ADMIN",
  organizationName: "浦江街道",
};

async function fulfill(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({
      code: status === 200 ? 0 : status,
      message: status === 200 ? "成功" : "网页触发验证码，无法自动读取",
      data,
    }),
  });
}

async function authenticate(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem("jianda_token", "p0-isolated-token");
    localStorage.setItem("jianda_user_info", JSON.stringify(user));
  }, orgUser);
}

test("机构管理员可单次导入未登记网页且不会获得可信来源操作", async ({
  page,
}) => {
  await authenticate(page);
  let importPayload: Record<string, unknown> | undefined;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/web-articles/preview-any") {
      return fulfill(route, {
        title: "社区防暑服务通知",
        source_name: "示例公开网页",
        canonical_url: "https://content.example.org/notices/1",
        original_domain: "example.org",
        canonical_domain: "content.example.org",
        canonical_confirmation_required: true,
        published_at: "2026-07-30T08:00:00+08:00",
        content_preview: "高温期间开放社区纳凉点。",
        content_kind: "COMMUNITY_SERVICE",
        authority_level: "UNVERIFIED",
        robots_allowed: true,
        robots_status: "ALLOWED",
        warnings: [],
        images: [],
        cover_image_type: "CATEGORY_DEFAULT",
        image_cached: false,
        trust_status: "UNVERIFIED",
        external_source_verified: false,
      });
    }
    if (path === "/api/web-articles/import-once") {
      importPayload = request.postDataJSON();
      return fulfill(route, {
        documentId: 701,
        imageReviewRequired: false,
        aiQueueStatus: "WAITING_APPROVAL",
      });
    }
    if (path === "/api/documents/701/process") {
      return fulfill(route, { status: "PROCESSING", jobId: 1701, progress: 25 });
    }
    return fulfill(route, []);
  });

  await page.goto(`${institutionUrl}/documents/upload`);
  await page.getByRole("button", { name: "导入网页文章" }).click();
  await page
    .getByLabel("公开网页 URL")
    .fill("https://example.org/redirecting-notice");
  await page.getByRole("button", { name: "识别网页内容" }).click();
  await expect(page.getByText("未核验网页 · 仅本次导入")).toBeVisible();
  await expect(page.getByText("example.org", { exact: true })).toBeVisible();
  await expect(page.getByText("content.example.org", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "仅本次导入" })).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "核验并保存为可信来源" }),
  ).toHaveCount(0);
  await page.getByLabel(/我已核对跳转后的最终域名/).check();
  await page.getByRole("button", { name: "仅本次导入" }).click();
  expect(importPayload).toEqual({
    url: "https://example.org/redirecting-notice",
    canonicalConfirmed: true,
  });
  await expect(page).toHaveURL(/\/documents\/701\/process\?.*jobId=1701/);
});

test("抓取失败时可粘贴正文并作为未核验材料进入处理", async ({ page }) => {
  await authenticate(page);
  let pastedPayload: Record<string, unknown> | undefined;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/web-articles/preview-any") return fulfill(route, null, 502);
    if (path === "/api/web-articles/import-pasted") {
      pastedPayload = request.postDataJSON();
      return fulfill(route, {
        documentId: 702,
        imageReviewRequired: false,
        aiQueueStatus: "WAITING_APPROVAL",
      });
    }
    if (path === "/api/documents/702/process") {
      return fulfill(route, { status: "PROCESSING", jobId: 1702, progress: 25 });
    }
    return fulfill(route, []);
  });

  await page.goto(`${institutionUrl}/documents/upload`);
  await page.getByRole("button", { name: "导入网页文章" }).click();
  await page.getByLabel("公开网页 URL").fill("https://blocked.example.org/a");
  await page.getByRole("button", { name: "识别网页内容" }).click();
  await expect(page.getByRole("alert")).toContainText("验证码");
  await page.getByText("网页无法直接解析时怎么办？").click();
  await page.getByRole("button", { name: "粘贴正文导入" }).click();
  await page.getByLabel("文章标题").fill("社区临时服务公告");
  await page.getByLabel("来源名称（可选）").fill("待核验来源");
  await page.getByLabel("公开正文").fill("这是从公开页面复制并由机构人员核对的正文。");
  await page.getByRole("button", { name: "导入粘贴正文" }).click();
  expect(pastedPayload).toMatchObject({
    url: "https://blocked.example.org/a",
    title: "社区临时服务公告",
    sourceName: "待核验来源",
    body: "这是从公开页面复制并由机构人员核对的正文。",
    contentKind: "SERVICE_NOTICE",
  });
  await expect(page).toHaveURL(/\/documents\/702\/process\?.*jobId=1702/);
});

test("处理页按 jobId 选择失败任务并显示 External 调用诊断", async ({
  page,
}) => {
  await authenticate(page);
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    if (path === "/api/documents/88") {
      return fulfill(route, {
        id: 88,
        title: "真实模型失败诊断材料",
        raw_text: "材料正文",
        source_type: "PDF",
        processing_status: "FAILED",
      });
    }
    if (path === "/api/documents/88/fields") return fulfill(route, []);
    if (path === "/api/documents/88/generated") return fulfill(route, []);
    if (path === "/api/documents/88/segments") return fulfill(route, [{ id: 1 }]);
    if (path === "/api/documents/88/jobs") {
      return fulfill(route, [
        {
          id: 901,
          status: "SUCCEEDED",
          stage: "SAVING_RESULT",
          progress: 100,
        },
        {
          id: 902,
          status: "FAILED",
          stage: "fact_validation",
          progress: 60,
          error_message: "模型未生成可追溯关键字段",
          reason_code: "NO_TRACEABLE_REVIEW_CONTENT",
          provider_id: "external",
          model_id: "deepseek-v4-flash",
          provider_request_id: "request-safe-902",
          response_fingerprint: "1234567890abcdef",
          crossed_provider_boundary: true,
        },
      ]);
    }
    return fulfill(route, []);
  });

  await page.goto(`${institutionUrl}/documents/88/process?jobId=902`);
  await expect(page.getByText("模型未生成可追溯关键字段")).toBeVisible();
  await expect(page.getByText("原因代码：NO_TRACEABLE_REVIEW_CONTENT")).toBeVisible();
  await expect(page.getByText("Provider：external")).toBeVisible();
  await expect(page.getByText("模型：deepseek-v4-flash")).toBeVisible();
  await expect(page.getByText("请求编号：request-safe-902")).toBeVisible();
  await expect(page.getByText("响应指纹：1234567890abcdef")).toBeVisible();
  await expect(page.getByText("已跨过真实模型调用边界：是")).toBeVisible();
});
