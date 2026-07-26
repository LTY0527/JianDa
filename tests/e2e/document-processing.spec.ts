import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const documentId = process.env.JIANDA_REVIEW_DOCUMENT_ID ?? "13";

test("uploaded PDF review shows real source and traceable mock fields", async ({
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

  await page.goto(`${institutionUrl}/documents/${documentId}/review`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.getByRole("heading", { name: "原文对照审核" })).toBeVisible();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(page.locator(".source-pane")).toContainText("银龄数字生活");
  await expect(page.locator(".source-pane")).toContainText("本街道常住居民");

  const expectedValues = [
    "55周岁及以上常住居民",
    "2026年8月1日",
    "2026年8月15日",
    "浦江街道社区服务中心201教室",
    "免费",
    "021-5688-1026",
    "智能手机、充电线、身份证",
    "不提供银行卡、支付密码或短信验证码",
  ];
  const fieldInputs = page.locator(".review-fields textarea");
  await expect(fieldInputs).toHaveCount(expectedValues.length);
  for (let index = 0; index < expectedValues.length; index += 1) {
    await expect(fieldInputs.nth(index)).toHaveValue(expectedValues[index]);
  }
  for (const unrelated of [
    "年满 80 周岁",
    "生活补贴",
    "银行卡复印件",
    "近期一寸",
  ]) {
    await expect(page.locator(".review-page")).not.toContainText(unrelated);
    for (let index = 0; index < expectedValues.length; index += 1) {
      await expect(fieldInputs.nth(index)).not.toHaveValue(new RegExp(unrelated));
    }
  }

  const cards = page.locator(".review-fields article");
  await expect(cards).toHaveCount(8);
  await cards.nth(1).click();
  await expect(cards.nth(1)).toHaveClass(/active/);
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-document-13-review.png"),
    fullPage: false,
  });
  expect(consoleErrors).toEqual([]);
});
