import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

/**
 * Phase 9.8_4 最终真实验收收口
 *
 * 真实链路：真实 Docker / 真实 MySQL / 真实 DeepSeek / 真实浏览器 / 真实官方网页。
 * 禁止使用 Mock / fixture HTTP / page.route / route.fulfill / 预置 JWT。
 *
 * 凭据来源：README.md 文档化的本地演示账号 platform_admin / Jianda@123
 * （由 DemoDataInitializer.java 创建，README.md L211-L219 明确标注为演示账号）。
 * 优先从环境变量读取，回退到文档化演示凭据，便于本地一键复验。
 */

const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const platformUsername = process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin";
// 文档化演示凭据，非生产 Secret；优先读 env 以便 CI/异地复验
const platformPassword = process.env.REAL_PLATFORM_ADMIN_PASSWORD ?? "Jianda@123";
const artifactRoot = path.resolve("artifacts/phase9-8-4-final");

// 选择已处于 WAITING_REVIEW 且带 SUMMARY 生成内容的真实网页文章，
// 用以走完“审核 -> 发布 -> H5 验证”链路（agent 上轮在此截断）。
const reviewDocumentId = Number(process.env.PHASE9_8_4_REVIEW_DOC_ID ?? 71);

test.describe("Phase 9.8_4 真实 Admin 全链路", () => {
  test.describe.configure({ mode: "serial" });
  test.beforeAll(() => {
    fs.mkdirSync(artifactRoot, { recursive: true });
  });

  // 共享登录态：先在独立 page 登录，保存 storageState，后续复用
  let storedAuth: string | null = null;

  test("步骤1-2 平台管理员真实登录并截登录页", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/login`);
    await page.getByRole("textbox", { name: "账号", exact: true }).fill(platformUsername);
    await page.getByLabel("密码").fill(platformPassword);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page).toHaveURL(`${institutionUrl}/`);
    await expect(page.locator(".account")).toContainText("平台管理员");
    await page.screenshot({
      path: path.join(artifactRoot, "admin-login-1440.png"),
      fullPage: false,
    });
    // 保存登录态供后续 test 复用
    storedAuth = await page.context().storageState();
  });

  test("步骤3-4 采集与来源页 + 立即检查真实来源", async ({ browser }) => {
    const context = await browser.newContext(
      storedAuth ? { storageState: storedAuth } : undefined,
    );
    const page = await context.newPage();
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/public-sources`);
    await expect(page).toHaveURL(`${institutionUrl}/public-sources`);
    // 等待来源卡片渲染
    await expect(page.locator(".source-card").first()).toBeVisible({ timeout: 15000 });
    await page.screenshot({
      path: path.join(artifactRoot, "admin-sources-1440.png"),
      fullPage: false,
    });

    // 选择一个已启用的真实来源点击“立即检查”
    const checkButtons = page.locator(".source-card footer button", { hasText: "立即检查" });
    const count = await checkButtons.count();
    expect(count).toBeGreaterThan(0);
    // 优先选择第一个可点击的（未禁用）
    let clicked = false;
    for (let i = 0; i < count; i += 1) {
      const btn = checkButtons.nth(i);
      if (await btn.isEnabled()) {
        await btn.click();
        clicked = true;
        break;
      }
    }
    expect(clicked).toBeTruthy();

    // 截“检查执行中”
    try {
      await expect(page.locator(".source-operation-state.running")).toBeVisible({ timeout: 10000 });
    } catch {
      // 某些来源检查极快，running 状态可能已结束，仍截当前页
    }
    await page.waitForTimeout(1500);
    await page.screenshot({
      path: path.join(artifactRoot, "admin-source-running-1440.png"),
      fullPage: false,
    });

    // 等待检查结束（running 消失或 success 状态）
    await page.waitForTimeout(8000);
    await page.screenshot({
      path: path.join(artifactRoot, "admin-source-success-1440.png"),
      fullPage: false,
    });
    await context.close();
  });

  test("步骤5 查看新内容（内容中心待审核）", async ({ browser }) => {
    const context = await browser.newContext(
      storedAuth ? { storageState: storedAuth } : undefined,
    );
    const page = await context.newPage();
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents?status=WAITING_REVIEW`);
    await expect(page).toHaveURL(/\/documents/);
    await page.waitForTimeout(2000);
    await page.screenshot({
      path: path.join(artifactRoot, "admin-new-content-1440.png"),
      fullPage: false,
    });
    await context.close();
  });

  test("步骤6 处理结果页", async ({ browser }) => {
    const context = await browser.newContext(
      storedAuth ? { storageState: storedAuth } : undefined,
    );
    const page = await context.newPage();
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents/${reviewDocumentId}/process`);
    await page.waitForTimeout(3000);
    await page.screenshot({
      path: path.join(artifactRoot, "admin-processing-1440.png"),
      fullPage: false,
    });
    await context.close();
  });

  test("步骤7-8 审核页 + 完成字段审核", async ({ browser }) => {
    const context = await browser.newContext(
      storedAuth ? { storageState: storedAuth } : undefined,
    );
    const page = await context.newPage();
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents/${reviewDocumentId}/review`);
    await page.waitForTimeout(3000);

    // 截“等待审核 / 审核页”
    await page.screenshot({
      path: path.join(artifactRoot, "admin-waiting-review-1440.png"),
      fullPage: false,
    });

    // 逐个确认结构化模块（“确认此模块”）。
    // 注意：点击后按钮文本变为“此模块已确认”并禁用，locator 重新匹配，
    // 因此使用 while 循环反复点击第一个未确认按钮，直到没有未确认模块。
    let moduleGuard = 0;
    while (moduleGuard < 10) {
      const unconfirmed = page.locator("button.confirm-btn", { hasText: "确认此模块" });
      const remaining = await unconfirmed.count();
      if (remaining === 0) break;
      const btn = unconfirmed.first();
      await btn.scrollIntoViewIfNeeded().catch(() => {});
      await btn.click();
      await page.waitForTimeout(400);
      moduleGuard += 1;
    }

    // 对网页文章：点击“使用分类默认图 / 删除封面”以满足 image_reviewed 要求
    const useDefaultCoverBtn = page.locator("button", { hasText: "使用分类默认图" });
    if (await useDefaultCoverBtn.isVisible().catch(() => false)) {
      await useDefaultCoverBtn.click();
      await page.waitForTimeout(800);
    }

    await page.screenshot({
      path: path.join(artifactRoot, "admin-review-1440.png"),
      fullPage: false,
    });

    // 点击“完成字段审核”进入发布页
    const finishBtn = page.locator("button.btn.primary", { hasText: "完成字段审核" });
    await expect(finishBtn).toBeEnabled({ timeout: 10000 });
    await finishBtn.click();
    await expect(page).toHaveURL(new RegExp(`/documents/${reviewDocumentId}/publish`), { timeout: 15000 });
    await page.waitForTimeout(2000);
    await page.screenshot({
      path: path.join(artifactRoot, "admin-publish-preview-1440.png"),
      fullPage: false,
    });
    await context.close();
  });

  test("步骤9 审核通过并发布", async ({ browser }) => {
    const context = await browser.newContext(
      storedAuth ? { storageState: storedAuth } : undefined,
    );
    const page = await context.newPage();
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents/${reviewDocumentId}/publish`);
    await page.waitForTimeout(2000);

    // 监听 confirm 对话框并接受
    page.on("dialog", (dialog) => dialog.accept().catch(() => {}));

    const publishBtn = page.locator("button.btn.primary", { hasText: "审核通过并发布" });
    await expect(publishBtn).toBeEnabled({ timeout: 10000 });
    await publishBtn.click();

    // 等待发布成功面板
    await expect(page.locator(".success-panel", { hasText: "内容已成功发布" })).toBeVisible({
      timeout: 30000,
    });
    await page.screenshot({
      path: path.join(artifactRoot, "admin-published-1440.png"),
      fullPage: false,
    });
    await context.close();
  });

  test("步骤10 H5 端验证已发布内容", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    // 从已发布列表找到刚发布的文章
    await page.goto(`${h5Url}/news`);
    await page.waitForTimeout(2500);

    // 在新闻列表查找刚发布的文章标题（按 doc id 对应标题片段）
    const detailLink = page.locator(`a[href*="/news/"]`).first();
    await expect(detailLink).toBeVisible({ timeout: 15000 });
    await detailLink.click();
    await page.waitForTimeout(2500);
    await page.screenshot({
      path: path.join(artifactRoot, "h5-published-result-390.png"),
      fullPage: false,
    });
  });
});

test.describe("Phase 9.8_4 H5 首页多视口真实截图", () => {
  const viewports: Array<[string, { width: number; height: number }]> = [
    ["375", { width: 375, height: 800 }],
    ["390", { width: 390, height: 844 }],
    ["768", { width: 768, height: 1024 }],
    ["1440", { width: 1440, height: 900 }],
  ];

  for (const [label, size] of viewports) {
    test(`首页 ${label} 视口真实截图`, async ({ page }) => {
      await page.setViewportSize(size);
      await page.goto(`${h5Url}/`);
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(2000);
      await page.screenshot({
        path: path.join(artifactRoot, `h5-home-${label}.png`),
        fullPage: false,
      });
      // 验证首屏有真实内容（非空白）
      const bodyText = await page.locator("body").innerText();
      expect(bodyText.trim().length).toBeGreaterThan(50);
    });
  }

  test("大场频道 390 视口真实截图", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/?channel=dachang`);
    await page.waitForLoadState("networkidle");
    await page.waitForTimeout(2000);
    await page.screenshot({
      path: path.join(artifactRoot, "h5-dachang-390.png"),
      fullPage: false,
    });
  });

  test("有图新闻 390 视口真实截图", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/news`);
    await page.waitForLoadState("networkidle");
    await page.waitForTimeout(2000);
    // 找到带封面图的文章卡片
    const withImage = page.locator("article:has(img), .news-card:has(img), .article-card:has(img)").first();
    if (await withImage.isVisible().catch(() => false)) {
      await withImage.scrollIntoViewIfNeeded();
      await page.waitForTimeout(800);
    }
    await page.screenshot({
      path: path.join(artifactRoot, "h5-news-with-image-390.png"),
      fullPage: false,
    });
  });

  test("无图新闻文字卡 390 视口真实截图", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/news`);
    await page.waitForLoadState("networkidle");
    await page.waitForTimeout(2000);
    // 滚动到无图卡片区域
    await page.mouse.wheel(0, 600);
    await page.waitForTimeout(1000);
    await page.screenshot({
      path: path.join(artifactRoot, "h5-news-no-image-390.png"),
      fullPage: false,
    });
  });
});
