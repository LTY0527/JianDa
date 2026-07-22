import { expect, test } from "@playwright/test";
import path from "node:path";
import os from "node:os";

test("机构端真实登录并进入工作台", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("http://localhost:5173/login");
  await expect(page).toHaveTitle(/简达/);
  await expect(page.getByRole("heading", { name: "登录机构工作台" })).toBeVisible();
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL("http://localhost:5173/");
  await expect(page.getByRole("heading", { name: /上午好/ })).toBeVisible();
  await page.screenshot({ path: path.join(os.tmpdir(), "jianda-institution-desktop.png"), fullPage: false });
  expect(errors).toEqual([]);
});

test("用户 H5 加载公开内容并切换字号与收藏", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("http://localhost:5174/");
  await expect(page).toHaveTitle(/简达/);
  await expect(page.getByRole("heading", { name: "办事不犯难，信息看得懂" })).toBeVisible();
  const firstCard = page.locator(".content-row").first();
  await expect(firstCard).toBeVisible();
  await firstCard.click();
  await expect(page).toHaveURL(/\/guide\//);
  await expect(page.locator(".article-head h1")).not.toHaveText("正在加载…");
  await page.getByRole("button", { name: /18px/ }).click();
  await expect(page.getByRole("button", { name: /20px/ })).toBeVisible();
  await page.getByRole("button", { name: "收藏" }).click();
  await expect(page.getByRole("button", { name: "已收藏" })).toBeVisible();
  await page.screenshot({ path: path.join(os.tmpdir(), "jianda-h5-mobile.png"), fullPage: false });
  expect(errors).toEqual([]);
});
