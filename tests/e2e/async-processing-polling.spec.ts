import { expect, test, type Page, type Route } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

function api(data: unknown) {
  return {
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: 0, message: "成功", data }),
  };
}

async function authenticate(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "async-processing-token");
    localStorage.setItem(
      "jianda_user_info",
      JSON.stringify({
        id: 1,
        organizationId: 1,
        username: "org_admin",
        displayName: "机构管理员",
        role: "ORG_ADMIN",
        organizationName: "简达演示机构",
      }),
    );
  });
}

async function mockProcessing(
  page: Page,
  statusForDetail: () => "PROCESSING" | "WAITING_REVIEW",
) {
  const fulfill = (route: Route, data: unknown) => route.fulfill(api(data));
  let currentStatus: "PROCESSING" | "WAITING_REVIEW" = "PROCESSING";
  await page.route("**/api/documents/108**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/fields")) return fulfill(route, []);
    if (path.endsWith("/segments")) {
      return fulfill(route, [
        { id: 81, page_no: 1, segment_no: 1, text: "标准规范原文" },
      ]);
    }
    if (path.endsWith("/generated")) {
      return fulfill(
        route,
        currentStatus === "WAITING_REVIEW"
          ? [
              {
                id: 91,
                content_type: "STANDARD_SECTIONS",
                title: "标准规范结构",
                content_json: JSON.stringify({
                  scope: "适用于社区养老服务",
                }),
                content_text: "适用于社区养老服务",
              },
            ]
          : [],
      );
    }
    if (path.endsWith("/jobs")) {
      return fulfill(route, [
        {
          id: 52,
          status:
            currentStatus === "WAITING_REVIEW" ? "SUCCEEDED" : "PROCESSING",
          stage:
            currentStatus === "WAITING_REVIEW"
              ? "SUCCEEDED"
              : "EXTRACTING_FACTS",
          progress: currentStatus === "WAITING_REVIEW" ? 100 : 35,
          total_tokens: currentStatus === "WAITING_REVIEW" ? 1680 : 0,
          cache_hit: false,
          started_at: "2026-07-30T00:00:00+08:00",
        },
      ]);
    }
    currentStatus = statusForDetail();
    return fulfill(route, {
      id: 108,
      title: "养老服务标准规范",
      source_type: "PDF",
      content_kind: "STANDARD_SPECIFICATION",
      processing_status: currentStatus,
      raw_text: "标准规范原文",
      page_count: 12,
      organization_name: "简达演示机构",
      updated_at: "2026-07-30T00:00:00+08:00",
    });
  });
}

test.beforeEach(async ({ page }) => {
  await authenticate(page);
});

test("处理页每两秒轮询并在只有类型模块时开放审核入口", async ({ page }) => {
  let detailRequests = 0;
  await mockProcessing(page, () => {
    detailRequests += 1;
    return detailRequests >= 3 ? "WAITING_REVIEW" : "PROCESSING";
  });

  await page.goto(`${institutionUrl}/documents/108/process`);
  await expect(page.getByText("正在分析材料关键事实")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "进入原文对照审核" }),
  ).toBeVisible({ timeout: 7_000 });
  expect(detailRequests).toBeGreaterThanOrEqual(3);
  await expect(page.getByText("处理完成，可进入原文对照审核")).toBeVisible();
});

test("刷新页面后从后端恢复已有任务终态", async ({ page }) => {
  let completed = false;
  await mockProcessing(page, () =>
    completed ? "WAITING_REVIEW" : "PROCESSING",
  );

  await page.goto(`${institutionUrl}/documents/108/process`);
  await expect(page.getByText("正在分析材料关键事实")).toBeVisible();
  completed = true;
  await page.reload();

  await expect(
    page.getByRole("link", { name: "进入原文对照审核" }),
  ).toBeVisible();
  await expect(
    page.getByText("已生成 0 个可追溯字段和 1 个内容模块"),
  ).toBeVisible();
});
