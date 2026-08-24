import { expect, test, type Page } from "@playwright/test";
import os from "node:os";
import path from "node:path";
import { acceptanceArtifactPath } from "./support/acceptanceArtifacts";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_TEST_URL ?? "http://127.0.0.1:5173";
const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1:5174";

async function login(page: Page, username: string) {
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(username);
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
}

test.describe.serial("Phase 7 navigation and public information flow", () => {
  const ownedDocumentIds = new Set<number>();
  let ownedToken = "";

  test.afterEach(async ({ request }) => {
    if (!ownedToken || ownedDocumentIds.size === 0) return;
    const headers = { Authorization: `Bearer ${ownedToken}` };
    for (const documentId of [...ownedDocumentIds]) {
      const response = await request.post(
        `http://127.0.0.1:8080/api/documents/${documentId}/withdraw`,
        { headers },
      );
      if (response.ok() || response.status() === 404 || response.status() === 409) {
        ownedDocumentIds.delete(documentId);
      }
    }
  });

  test("H5 root pages do not show a back button", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(h5Url);
    await expect(page.getByRole("button", { name: "返回" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "简达首页" })).toBeVisible();
  });

  test("消费级 App 五项导航、资讯频道和办事筛选可用", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(h5Url);
    const navigation = page.getByRole("navigation", { name: "主要导航" });
    for (const label of ["首页", "邻里", "简达助手", "办事", "我的"]) {
      await expect(navigation.getByRole("link", { name: label, exact: true })).toBeVisible();
    }
    await expect(page.getByRole("heading", { name: "推荐内容" })).toBeVisible();
    await page.getByRole("link", { name: "查看全部" }).click();
    await expect(page.getByRole("heading", { name: "权威资讯" })).toBeVisible();
    await page.getByRole("button", { name: "健康", exact: true }).click();
    await page.getByRole("button", { name: "重要", exact: true }).click();
    await expect(page.locator(".channel-tabs .active")).toHaveText("健康");
    await navigation.getByRole("link", { name: "办事", exact: true }).click();
    await expect(page.getByRole("heading", { name: "办事行动中心" })).toBeVisible();
    await page.getByLabel("服务对象").selectOption("老年人");
    await expect(page.getByText(/\d+ 个事项/)).toBeVisible();
    await navigation.getByRole("link", { name: "简达助手", exact: true }).click();
    await expect(page.getByRole("heading", { name: "简达助手" })).toBeVisible();
    await navigation.getByRole("link", { name: "我的", exact: true }).click();
    await expect(page.getByRole("heading", { name: "游客浏览" })).toBeVisible();
    await expect(page.getByRole("link", { name: "居民登录" })).toBeVisible();
  });

  test("用户端在 375、768 和 1440 宽度无横向溢出", async ({ page }) => {
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: width === 375 ? 812 : 900 });
      for (const route of ["/", "/listen", "/news", "/services", "/assistant", "/profile"]) {
        await page.goto(`${h5Url}${route}`);
        await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
      }
    }
    await page.goto(h5Url);
    await page.screenshot({ path: path.join(os.tmpdir(), "jianda-h5-home-1440.png"), fullPage: true });
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
  }, testInfo) => {
    test.skip(
      process.env.RUN_MUTATING_E2E !== "1",
      "该流程会调用当前 AI Provider 并写入本地业务数据，仅在显式授权时运行",
    );
    test.setTimeout(90_000);
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

    await page.getByRole("button", { name: "本地示例导入" }).click();
    await expect(page.locator(".fixture-list article")).toHaveCount(3);
    const runId = Date.now();
    const title = "高血压患者夏季日常管理提示（人工验收）";
    const token = await page.evaluate(() => localStorage.getItem("jianda_token"));
    ownedToken = token || "";
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
    ownedDocumentIds.add(documentId);
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
    await page.screenshot({
      path: acceptanceArtifactPath(
        testInfo,
        "admin-publish-success-1440.png",
        page.viewportSize() ?? { width: 1440, height: 900 },
      ),
      fullPage: true,
    });
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
    await publicPage.getByRole("button", { name: "收藏", exact: true }).click();
    await expect(publicPage.getByRole("button", { name: "已收藏", exact: true })).toBeVisible();
    await publicPage.getByRole("link", { name: /问问这个事项/ }).click();
    await expect(publicPage.getByText("正在询问这项内容")).toBeVisible();
    await expect(publicPage.getByText(title, { exact: true })).toBeVisible();
    const assistantQuestion = `这项内容编号${runId}需要注意什么？`;
    await publicPage.getByLabel("输入您想了解的问题").fill(assistantQuestion);
    await publicPage.getByRole("button", { name: "发送问题" }).click();
    await expect(publicPage.getByRole("heading", { name: "回答依据" })).toBeVisible();
    await expect(publicPage.locator(".assistant-citation").filter({ hasText: title })).toBeVisible();
    await publicPage.screenshot({ path: path.join(os.tmpdir(), "jianda-h5-assistant-citation-375.png"), fullPage: true });
    await publicPage.getByRole("link", { name: "历史会话" }).click();
    await expect(publicPage.getByText(assistantQuestion, { exact: true })).toBeVisible();
    await publicPage.getByRole("button", { name: "返回" }).click();
    await expect(publicPage).toHaveURL(new RegExp("/assistant"));
    await publicPage.goto(`${h5Url}/profile`);
    await expect(publicPage.getByRole("link", { name: /我的收藏/ })).toContainText("1 条");
    await expect(publicPage.getByRole("link", { name: /历史浏览/ })).toContainText("1 条");
    await publicPage.getByRole("link", { name: /历史浏览/ }).click();
    await expect(publicPage.getByText(title, { exact: true })).toBeVisible();
    await publicPage.goto(`${h5Url}/settings`);
    await publicPage.getByRole("button", { name: "20", exact: true }).click();
    await publicPage.reload();
    await expect(publicPage.getByText("当前 20 像素")).toBeVisible();
    expect(await publicPage.evaluate(() => localStorage.getItem("jianda_font"))).toBe("20");
    await publicPage.goto(`${h5Url}/favorites`);
    await expect(publicPage.getByText(title, { exact: true })).toBeVisible();
    await publicPage.goto(href!);
    await publicPage.getByRole("button", { name: /20px/ }).click();
    await expect(
      publicPage.getByRole("button", { name: /22px/ }),
    ).toBeVisible();
    await publicPage
      .getByRole("link", { name: "提取文本", exact: true })
      .click();
    await expect(publicPage).toHaveURL(/\/original\//);
    await expect(publicPage.locator(".original-text")).toContainText(
      "夏季气温较高，高血压患者应按医嘱规律服药",
    );
    await expect(publicPage.locator(".original-text")).toBeVisible();
    expect(await publicPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    await publicPage.screenshot({ path: path.join(os.tmpdir(), "jianda-public-info-h5.png"), fullPage: true });
    await publicPage.getByRole("button", { name: "返回" }).click();
    await expect(publicPage).toHaveURL(href!);

    const directPage = await page.context().newPage();
    await directPage.setViewportSize({ width: 768, height: 1024 });
    await directPage.goto(href!);
    await directPage.getByRole("button", { name: "返回" }).click();
    await expect(directPage).toHaveURL(`${h5Url}/services`);
    expect(await directPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    await directPage.screenshot({ path: path.join(os.tmpdir(), "jianda-h5-services-768.png"), fullPage: true });
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
    ownedDocumentIds.delete(documentId);

    await publicPage.goto(href!);
    await expect(
      publicPage.getByText("内容暂时无法读取，可能已撤回"),
    ).toBeVisible();
    await publicPage.goto(`${h5Url}/`);
    await expect(publicPage.getByText(title, { exact: true })).toHaveCount(0);
    await publicPage.goto(`${h5Url}/favorites`);
    await expect(publicPage.getByText(title, { exact: true })).toHaveCount(0);
    await publicPage.evaluate(() => localStorage.removeItem("jianda_assistant_session"));
    await publicPage.goto(`${h5Url}/assistant`);
    await publicPage.getByLabel("输入您想了解的问题").fill(`校验编号${runId}`);
    await publicPage.getByRole("button", { name: "发送问题" }).click();
    await expect(publicPage.getByText(/没有找到足够可靠的依据/)).toBeVisible();
    await expect(publicPage.locator(".assistant-citation").filter({ hasText: title })).toHaveCount(0);
    expect(consoleErrors).toEqual([]);
  });
});
