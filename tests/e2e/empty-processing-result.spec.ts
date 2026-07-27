import { expect, test, type Page } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

async function mockDocument(page: Page, status: "WAITING_REVIEW" | "FAILED") {
  await page.addInitScript(() => {
    localStorage.setItem("jianda_token", "test-token");
  });
  await page.route("**/api/documents/99**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const ok = (data: unknown) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: 0, message: "ok", data }),
      });
    if (request.method() === "POST" && path.endsWith("/process")) {
      return route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: 503,
          message: "AI未生成可追溯的关键字段，请检查模型输出后重新处理",
          data: null,
        }),
      });
    }
    if (path.endsWith("/fields")) return ok([]);
    if (path.endsWith("/generated")) return ok([]);
    if (path.endsWith("/segments")) {
      return ok([{ id: 7, page_no: 1, segment_no: 1, text: "真实原文" }]);
    }
    if (path.endsWith("/jobs")) {
      return ok([
        {
          id: 5,
          status: status === "FAILED" ? "FAILED" : "SUCCEEDED",
          progress: 100,
          error_message:
            status === "FAILED"
              ? "AI未生成可追溯的关键字段，请检查模型输出后重新处理"
              : null,
        },
      ]);
    }
    return ok({
      id: 99,
      title: "医院门诊预约调整告知",
      raw_text: "真实原文",
      page_count: 1,
      organization_name: "测试机构",
      processing_status: status,
    });
  });
}

test("processing page uses real page and segment counts and shows failed retry state", async ({
  page,
}) => {
  await mockDocument(page, "FAILED");
  await page.goto(`${institutionUrl}/documents/99/process`);

  await expect(page.getByText("共 1 页，1 个段落")).toBeVisible();
  await expect(
    page.getByText("AI未生成可追溯的关键字段，请检查模型输出后重新处理"),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "重新处理" })).toBeVisible();
  await expect(page.getByText("字段与通俗版已生成")).toHaveCount(0);
  await expect(page.getByText("共 3 页，12 个段落")).toHaveCount(0);
});

test("waiting review with zero fields shows explicit recovery actions", async ({
  page,
}) => {
  await mockDocument(page, "WAITING_REVIEW");
  await page.goto(`${institutionUrl}/documents/99/review`);

  await expect(
    page.getByRole("heading", { name: "本次处理未生成可审核字段" }),
  ).toBeVisible();
  await expect(
    page.getByText("本次处理未生成可审核字段，请重新处理或查看任务日志"),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "返回材料详情" })).toBeVisible();
  await expect(page.getByRole("button", { name: "重新处理" })).toBeVisible();
  await expect(page.getByRole("button", { name: "完成字段审核" })).toBeDisabled();
  await expect(page.locator(".compare")).toHaveCount(0);
});
