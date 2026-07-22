import { expect, test, type Page } from "@playwright/test";
import path from "node:path";
import os from "node:os";

const institutionUrl = "http://127.0.0.1:5173";
const h5Url = "http://127.0.0.1:5174";

async function login(page: Page, username: string) {
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(username);
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
}

test.describe.serial("Phase 6 public information flow", () => {
  test("机构管理员不能访问平台公开信息功能", async ({ page }) => {
    await login(page, "org_admin");
    await page.goto(`${institutionUrl}/public-import`);
    await expect(
      page.getByRole("heading", { name: "无权访问此页面" }),
    ).toBeVisible();
    await expect(page.getByText("仅对平台管理员开放")).toBeVisible();
  });

  test("平台管理员导入、处理、审核、发布并撤回权威公开信息", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });

    await page.setViewportSize({ width: 1440, height: 900 });
    await login(page, "platform_admin");
    await page.getByRole("link", { name: "公开信息导入" }).click();
    await expect(
      page.getByRole("heading", { name: "权威公开信息导入" }),
    ).toBeVisible();

    const fixtures = page.locator(".fixture-list article");
    await expect(fixtures).toHaveCount(3);
    const fixture = fixtures.filter({ hasText: "警惕冒充客服退款诈骗" });
    const title = "警惕冒充客服退款诈骗，守好养老钱";
    const importRow = page
      .locator(".import-history tbody tr")
      .filter({ hasText: title });
    if ((await importRow.count()) === 0) {
      await fixture.getByRole("button", { name: "导入", exact: true }).click();
      await expect(page.getByText(`“${title}”已导入`)).toBeVisible();
    }
    await expect(importRow).toBeVisible();
    const processButton = importRow.getByRole("button", { name: "发起 AI" });
    if ((await processButton.count()) > 0) await processButton.click();
    else await importRow.getByRole("link", { name: "去审核" }).click();

    await expect(
      page.getByRole("heading", { name: "原文对照审核" }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "“正规平台退款不会要求转账到所谓安全账户，也不会索要银行卡密码和验证码。”",
        { exact: true },
      ),
    ).toBeVisible();
    await page.getByRole("button", { name: "完成字段审核" }).click();

    await expect(
      page.getByRole("heading", { name: "审核与发布" }),
    ).toBeVisible();
    await expect(page.getByLabel("发布标题")).toHaveValue(title);
    await expect(page.getByLabel("来源名称")).toHaveValue("国家反诈中心");
    await page.getByRole("button", { name: "审核通过并发布" }).click();

    await expect(
      page.getByRole("heading", { name: "内容已成功发布" }),
    ).toBeVisible();
    const publicLink = page.getByRole("link", { name: "打开用户端" });
    const href = await publicLink.getAttribute("href");
    expect(href).toMatch(new RegExp("/guide/guide-[0-9]+$"));

    const publicPage = await page.context().newPage();
    await publicPage.setViewportSize({ width: 375, height: 812 });
    await publicPage.goto(href!);
    await expect(publicPage.locator(".article-head h1")).toHaveText(title);
    await expect(
      publicPage.getByText("国家反诈中心", { exact: true }),
    ).toBeVisible();
    await expect(
      publicPage.getByRole("heading", { name: "重要提醒" }),
    ).toBeVisible();
    await publicPage.getByRole("button", { name: /18px/ }).click();
    await expect(
      publicPage.getByRole("button", { name: /20px/ }),
    ).toBeVisible();
    await publicPage.getByRole("link", { name: /看原文/ }).click();
    await expect(
      publicPage.getByText(/近期有不法分子冒充电商或快递客服/),
    ).toBeVisible();
    await publicPage.screenshot({
      path: path.join(os.tmpdir(), "jianda-public-info-h5.png"),
      fullPage: true,
    });

    await page.getByRole("link", { name: "查看已发布内容" }).click();
    const publishedRow = page.locator("tbody tr").filter({ hasText: title });
    await expect(publishedRow).toBeVisible();
    page.once("dialog", (dialog) => dialog.accept());
    await publishedRow.getByRole("button", { name: "撤回" }).click();
    await expect(publishedRow).toHaveCount(0);

    await publicPage.goto(href!);
    await expect(
      publicPage.getByText("内容暂时无法读取，可能已撤回"),
    ).toBeVisible();
    expect(consoleErrors).toEqual([]);
  });
});
