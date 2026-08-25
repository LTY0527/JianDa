import { expect, test } from "@playwright/test";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/phase9-6-real-acceptance-ux/real-e2e");

test.describe("Phase 9.6 真实发布链路", () => {
  test.skip(process.env.JIANDA_DOCKER_COMPOSE_UP !== "1", "仅连接真实 Docker/MySQL 运行");

  test("文档 63 审核证据和用户端发布内容真实可见", async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });

    await page.goto(`${institutionUrl}/login`);
    await page.getByRole("textbox", { name: "账号", exact: true }).fill("platform_admin");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page).toHaveURL(`${institutionUrl}/`);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents/63/review`);
    await expect(page.getByRole("heading", { name: "原文对照审核" })).toBeVisible();
    await expect(page.getByText(/30余名市民代表先后走进百诺巧克力博物馆/).last()).toBeVisible();
    await page.screenshot({ path: path.join(artifactRoot, "03-review.png"), fullPage: false });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/news/news-63`);
    await expect(page.getByRole("heading", { name: "兴业惠民 共筑大场——大场镇2026年政府开放日" })).toBeVisible();
    await expect(page.getByRole("button", { name: "查看官方原文" })).toBeVisible();
    await expect(page.getByText("不能自行前往")).toHaveCount(0);
    await expect(page.getByText("仅限受邀", { exact: false })).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await page.screenshot({ path: path.join(artifactRoot, "05-h5-published-real-photo.png"), fullPage: false });
    expect(errors).toEqual([]);
  });
});
