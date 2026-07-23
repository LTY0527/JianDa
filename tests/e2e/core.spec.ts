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

test.describe.serial("Phase 7 navigation and public information flow", () => {
  test("H5 root pages do not show a back button", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(h5Url);
    await expect(page.getByRole("button", { name: "返回" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "简达首页" })).toBeVisible();
  });
  test("机构管理员不能访问平台公开信息功能", async ({ page }) => {
    await login(page, "org_admin");
    await page.goto(`${institutionUrl}/public-import`);
    await expect(
      page.getByRole("heading", { name: "无权访问此页面" }),
    ).toBeVisible();
    await expect(page.getByText("仅对平台管理员开放")).toBeVisible();
  });

  test("机构列表保留筛选且不显示返回", async ({ page }) => {
    await login(page, "platform_admin");
    await page.goto(`${institutionUrl}/documents`);
    await expect(page.getByRole("button", { name: "返回" })).toHaveCount(0);
    const search = page.getByPlaceholder("搜索材料标题或文件名");
    await search.fill("养老");
    await page.goto(`${institutionUrl}/`);
    await page.goto(`${institutionUrl}/documents`);
    await expect(search).toHaveValue("养老");
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

    await expect(page.locator(".fixture-list article")).toHaveCount(3);
    const runId = Date.now();
    const title = `高血压患者夏季日常管理提示 ${runId}`;
    const token = await page.evaluate(() => localStorage.getItem("jianda_token"));
    const headers = { Authorization: `Bearer ${token}` };
    const sourceResponse = await page.request.get("http://127.0.0.1:8080/api/public-sources", { headers });
    const sources = (await sourceResponse.json()).data;
    const source = sources.find((item: any) => item.enabled) || sources[0];
    const importResponse = await page.request.post("http://127.0.0.1:8080/api/public-sources/import/manual", {
      headers,
      data: {
        sourceId: source.id,
        title,
        sourceName: source.source_name,
        sourceType: source.source_type,
        sourceUrl: `${source.source_url.replace(/\/$/, "")}/phase7-${runId}`,
        publisher: source.publisher,
        publishedAt: "2026-07-20T00:00:00",
        body: `夏季气温较高，高血压患者应按医嘱规律服药，不可自行停药或减量。建议每日早晚测量血压并记录，适量补充水分，避免高温时段外出。如出现持续头痛、胸闷或血压明显异常，应及时就医。本条校验编号为${runId}。`,
        category: "健康",
      },
    });
    expect(importResponse.ok()).toBeTruthy();
    const documentId = (await importResponse.json()).data.documentId;
    const processResponse = await page.request.post(`http://127.0.0.1:8080/api/public-sources/imports/${documentId}/process`, { headers });
    expect(processResponse.ok()).toBeTruthy();
    await page.goto(`${institutionUrl}/documents/${documentId}/review`);

    await expect(
      page.getByRole("heading", { name: "原文对照审核" }),
    ).toBeVisible();
    await expect(page.getByRole("heading", { name: title, exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "返回" })).toBeVisible();
    const firstField = page.locator(".review-fields textarea").first();
    const originalFieldValue = await firstField.inputValue();
    await firstField.fill(`${originalFieldValue} `);
    page.once("dialog", (dialog) => dialog.dismiss());
    await page.getByRole("button", { name: "返回" }).click();
    await expect(page).toHaveURL(/\/documents\/\d+\/review$/);
    await firstField.fill(originalFieldValue);
    await expect(page.locator(".source-pane").getByText(/夏季气温较高，高血压患者应按医嘱规律服药/).first()).toBeVisible();

    await page.getByRole("button", { name: "完成字段审核" }).click();

    await expect(
      page.getByRole("heading", { name: "审核与发布" }),
    ).toBeVisible();
    await expect(page.getByLabel("发布标题")).toHaveValue(title);
    await expect(page.getByLabel("来源名称")).toHaveValue(source.source_name);
    page.once("dialog", (dialog) => dialog.accept());
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
      publicPage.getByText(source.source_name, { exact: true }),
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
      publicPage.getByText(/夏季气温较高，高血压患者应按医嘱规律服药/),
    ).toBeVisible();
    expect(await publicPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    await publicPage.screenshot({ path: path.join(os.tmpdir(), "jianda-public-info-h5.png"), fullPage: true });
    await publicPage.getByRole("button", { name: "返回" }).click();
    await expect(publicPage).toHaveURL(href!);

    const directPage = await page.context().newPage();
    await directPage.setViewportSize({ width: 768, height: 1024 });
    await directPage.goto(href!);
    await directPage.getByRole("button", { name: "返回" }).click();
    await expect(directPage).toHaveURL(`${h5Url}/`);
    expect(await directPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    await directPage.screenshot({ path: path.join(os.tmpdir(), "jianda-h5-home-768.png"), fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    await page.screenshot({ path: path.join(os.tmpdir(), "jianda-institution-1440.png"), fullPage: true });
    const adminTablet = await page.context().newPage();
    await adminTablet.setViewportSize({ width: 768, height: 1024 });
    await adminTablet.goto(`${institutionUrl}/documents`);
    expect(await adminTablet.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    await adminTablet.screenshot({ path: path.join(os.tmpdir(), "jianda-institution-768.png"), fullPage: true });
    await adminTablet.close();
    await directPage.close();

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
