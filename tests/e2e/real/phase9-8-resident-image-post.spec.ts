import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/phase9-8-real-acceptance");
const imagePath = path.join(artifactRoot, "resident-post-image.png");

test("真实注册居民发布图片帖子", async ({ page }) => {
  test.setTimeout(180_000);
  test.skip(process.env.JIANDA_ALLOW_REAL_RESIDENT === "1" ? false : true,
    "需要显式授权真实居民注册与单帖闭环");
  expect(fs.existsSync(imagePath)).toBeTruthy();
  fs.mkdirSync(artifactRoot, { recursive: true });

  const runId = `${Date.now()}`;
  const username = `p98_${runId}`;
  const nickname = `验收居民${runId.slice(-4)}`;
  const password = `Resident${runId.slice(-6)}A`;
  const content = `[P98-${runId}] 大场镇图文邻里真实验收帖，仅用于本地闭环验证。`;

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/resident/register?redirect=/neighborhood`);
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("昵称").fill(nickname);
  await page.locator('input[autocomplete="new-password"]').first().fill(password);
  await page.getByLabel("确认密码").fill(password);
  await page.getByRole("button", { name: "注册并登录" }).click();
  await expect(page).toHaveURL(`${h5Url}/neighborhood`);

  await page.locator('input[type="file"]').setInputFiles(imagePath);
  await expect(page.getByAltText("待发布图片 1")).toBeVisible();
  await page.getByLabel("分享一件对邻里有用的事").fill(content);
  const createResponsePromise = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/public/community/posts"
      && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "发布", exact: true }).click();
  const createResponse = await createResponsePromise;
  expect(createResponse.ok()).toBeTruthy();
  const postId = Number((await createResponse.json()).data.id);
  expect(postId).toBeGreaterThan(0);
  await expect(page.getByText(content)).toBeVisible();
  await expect(page.getByAltText(`${nickname}发布的图片 1`)).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, `resident-image-post-${postId}.png`), fullPage: true });
});
