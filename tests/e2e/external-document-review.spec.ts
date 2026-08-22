import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

test("document 16 shows real processing counts and traceable external fields", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill("org_admin");
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);

  await page.goto(`${institutionUrl}/documents/16/process`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(page.getByText("共 1 页，1 个段落")).toBeVisible();
  await expect(page.getByText("已生成 10 个可追溯字段")).toBeVisible();
  await expect(page.getByText("共 3 页，12 个段落")).toHaveCount(0);
  await expect(page.getByText("处理完成", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "进入原文对照审核" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/documents/16/review`);
  await expect(page.getByRole("heading", { name: "原文对照审核" })).toBeVisible();
  await expect(page.locator(".source-pane")).toContainText("医院门诊预约调整告知");
  await expect(page.locator(".pane-title").first()).toContainText("第 1 页 / 共 1 页");
  const showAllFields = page.getByRole("button", { name: "查看全部 10 项" });
  if (await showAllFields.isVisible()) await showAllFields.click();

  const fieldCards = page.locator(".review-fields article");
  await expect(fieldCards).toHaveCount(10);
  const values = await page
    .locator(".review-fields textarea")
    .evaluateAll((elements) =>
      elements.map((element) => (element as HTMLTextAreaElement).value),
    );
  expect(values).toEqual(
    expect.arrayContaining([
      "10月1日 → 10月8日",
      "10月2日 → 10月9日",
      "10月3日 → 10月10日",
      "9月28日18:00以前",
      "021-5558 7301",
    ]),
  );
  await expect(page.getByText("原文依据 · 第 1 页")).toHaveCount(10);
  const reviewAction = page.getByRole("button", {
    name: /完成字段审核|字段已审核|内容已发布/,
  });
  await expect(reviewAction).toBeVisible();
  if (await reviewAction.getAttribute("aria-disabled") !== "true" && await reviewAction.isEnabled()) {
    await expect(reviewAction).toBeEnabled();
  }

  await page.getByRole("button", { name: "原PDF", exact: true }).click();
  await expect(page.getByRole("region", { name: "PDF 在线阅读器" })).toBeVisible();
  await expect(page.getByRole("button", { name: "下载原文件" })).toBeVisible();
  await page.getByRole("button", { name: "提取文本", exact: true }).click();
  await expect(page.locator(".source-pane")).toContainText("医院门诊预约调整告知");

  await fieldCards.nth(6).click();
  await expect(fieldCards.nth(6)).toHaveClass(/active/);
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-document-16-review.png"),
    fullPage: false,
  });
  expect(consoleErrors).toEqual([]);
});
