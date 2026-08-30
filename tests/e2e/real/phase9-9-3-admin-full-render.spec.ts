import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const institutionUrl = process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const artifactRoot = path.resolve(
  process.env.JIANDA_FINAL_ACCEPTANCE_ARTIFACT_DIR
    ?? "artifacts/phase9-9-3-final",
);
const adminAccount = process.env.JIANDA_ADMIN_ACCOUNT ?? "platform_admin";
const adminPassword = process.env.JIANDA_ADMIN_PASSWORD ?? "Jianda@123";

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

async function adminLogin(page: import("@playwright/test").Page) {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${institutionUrl}/login`, { waitUntil: "domcontentloaded" });
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(adminAccount);
  await page.getByRole("textbox", { name: "密码", exact: true }).fill(adminPassword);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).not.toHaveURL(/\/login$/, { timeout: 15_000 });
}

test("REAL 管理端内容中心真实渲染并展示 6 个状态 Tab", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await adminLogin(page);
  await page.goto(`${institutionUrl}/documents`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "内容中心", exact: true })).toBeVisible({ timeout: 15_000 });
  const tabs = page.locator('nav[aria-label="内容状态"]');
  await expect(tabs).toBeVisible();
  for (const label of ["全部", "待处理", "待审核", "待发布", "已发布", "异常"]) {
    await expect(tabs.getByRole("button", { name: new RegExp(`^${label}`), exact: true })).toBeVisible();
  }
  await expect(page.getByRole("button", { name: "添加内容", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /刷新/ })).toBeVisible();

  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
  await page.screenshot({ path: path.join(artifactRoot, "admin-documents-1440.png"), fullPage: false });
});

test("REAL 管理端已发布内容真实渲染并暴露受控栏目调整入口", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await adminLogin(page);
  await page.goto(`${institutionUrl}/published`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "已发布内容", exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator("table thead th", { hasText: "栏目" })).toBeVisible();
  await expect(page.locator("table thead th", { hasText: "操作" })).toBeVisible();

  const adjustButton = page.getByRole("button", { name: /调整栏目/ }).first();
  const emptyHint = page.locator(".empty-state", { hasText: "暂无已发布内容" });
  await expect.poll(async () => await adjustButton.count() > 0 || await emptyHint.count() > 0 ? "ready" : "wait").toBe("ready");

  if (await adjustButton.count() > 0) {
    await adjustButton.click();
    const picker = page.locator(".channel-picker").first();
    await expect(picker).toBeVisible({ timeout: 5_000 });
    for (const label of ["健康", "养老", "助餐", "办事", "防诈", "活动", "社区"]) {
      await expect(picker.getByRole("button", { name: new RegExp(`^${label}`), exact: true })).toBeVisible();
    }
  }

  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
  await page.screenshot({ path: path.join(artifactRoot, "admin-published-channel-adjust-1440.png"), fullPage: false });
});

test("REAL 管理端添加内容弹窗真实渲染三种录入入口", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await adminLogin(page);
  await page.goto(`${institutionUrl}/documents`, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "添加内容", exact: true }).click();

  const dialog = page.locator('section[role="dialog"]');
  await expect(dialog).toBeVisible({ timeout: 5_000 });
  await expect(dialog.getByText("上传 PDF / 图片", { exact: false })).toBeVisible();
  await expect(dialog.getByText("粘贴网页链接", { exact: false })).toBeVisible();
  await expect(dialog.getByText("手工录入", { exact: false })).toBeVisible();

  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "admin-add-content-dialog-1440.png"), fullPage: false });
});

test("REAL 管理端上传材料页真实渲染", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await adminLogin(page);
  await page.goto(`${institutionUrl}/documents/upload`, { waitUntil: "networkidle" });

  await expect(page.getByRole("heading", { name: "新增材料", exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("button", { name: /上传 PDF 或图片/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /导入网页文章/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /上传并开始处理/ })).toBeVisible();
  expect(consoleErrors.filter((e) => !/favicon|404/i.test(e)).length).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "admin-upload-1440.png"), fullPage: false });
});
