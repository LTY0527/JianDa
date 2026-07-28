import { expect, test, type Page } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const dockerComposeUp = process.env.JIANDA_DOCKER_COMPOSE_UP === "1";

test.describe("Phase 9 Docker 真实数据验收", () => {
  test.skip(
    !dockerComposeUp,
    "需启动 Docker Compose 并设置 JIANDA_DOCKER_COMPOSE_UP=1",
  );

  async function loginAsOrganizationAdmin(page: Page) {
    const response = await page.request.post(
      `${institutionUrl}/api/auth/login`,
      {
        data: { username: "org_admin", password: "Jianda@123" },
      },
    );
    expect(response.status()).toBe(200);
    const payload = await response.json();
    await page.addInitScript(
      ({ token, user }) => {
        localStorage.setItem("jianda_token", token);
        localStorage.setItem("jianda_user_info", JSON.stringify(user));
      },
      { token: payload.data.token, user: payload.data.user },
    );
  }

  test("机构管理员可查看文档 27 的网页快照与现有 AI 结果", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await loginAsOrganizationAdmin(page);

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents/27/review`);
    await expect(page).toHaveTitle(/简达/);
    await expect(page.locator("#app")).not.toBeEmpty();
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "原文对照审核" })).toBeVisible();
    await expect(page.getByText("网页正文")).toBeVisible();
    await expect(page.getByText(/正文快照 · \d+ 字/)).toBeVisible();
    await expect(page.getByText(/已确认 4 \/ 4/)).toBeVisible();
    await expect(page.getByText("已发布", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "内容已发布" })).toBeDisabled();
    await expect(page.getByRole("link", { name: "查看官方原文" })).toHaveAttribute(
      "href",
      /^https?:\/\//,
    );
    await expect(page.locator(".review-fields article")).toHaveCount(4);
    await page.screenshot({
      path: path.join(os.tmpdir(), "jianda-phase9-live-review-1440.png"),
      fullPage: false,
    });

    expect(pageErrors).toEqual([]);
    expect(consoleErrors).toEqual([]);
  });

  test("已发布的文档 27 出现在用户端资讯流和详情页", async ({ page }) => {
    const publicDetailResponse = await page.request.get(
      `${h5Url}/api/public/items/news-27`,
    );
    expect(publicDetailResponse.status()).toBe(200);
    const publicDetail = (await publicDetailResponse.json()).data;

    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(h5Url);
    await expect(page.locator("#app")).not.toBeEmpty();
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
    await expect(page.getByText(publicDetail.title).first()).toBeVisible();

    await page.goto(`${h5Url}/news/news-27`);
    await expect(page.getByRole("heading", { name: publicDetail.title })).toBeVisible();
    await expect(page.getByText(publicDetail.source_name).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "查看官方原文" })).toBeVisible();
    await expect
      .poll(() =>
        page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      )
      .toBeTruthy();
    await page.screenshot({
      path: path.join(os.tmpdir(), "jianda-phase9-live-h5-390.png"),
      fullPage: false,
    });

    expect(pageErrors).toEqual([]);
    expect(consoleErrors).toEqual([]);
  });
});
