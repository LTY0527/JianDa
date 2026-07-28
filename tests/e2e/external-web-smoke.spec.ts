import { expect, test } from "@playwright/test";

const runExternal = process.env.RUN_EXTERNAL_SMOKE === "1";
const sourceUrl = process.env.EXTERNAL_SMOKE_URL ?? "";
const institutionUrl =
  process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";

test.describe("真实网页 External Provider 烟雾测试", () => {
  test.skip(!runExternal || !sourceUrl, "设置 RUN_EXTERNAL_SMOKE=1 和 EXTERNAL_SMOKE_URL 后执行");
  test.setTimeout(240_000);

  test("从机构端导入、处理、审核并发布未导入白名单网页", async ({ page }) => {
    await page.goto(`${institutionUrl}/login`);
    await page.getByRole("textbox", { name: "账号", exact: true }).fill("org_admin");
    await page.getByLabel("密码").fill("Jianda@123");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page).toHaveURL(`${institutionUrl}/`);
    await page.goto(`${institutionUrl}/documents/upload`);
    await page.getByRole("button", { name: "导入网页文章" }).click();
    await page.getByLabel("官方文章 URL").fill(sourceUrl);
    await page.getByRole("button", { name: "识别网页内容" }).click();
    await expect(page.locator(".web-preview-card h2")).not.toBeEmpty();
    const title = (await page.locator(".web-preview-card h2").textContent())?.trim() || "";
    await expect(page.getByText(/政府部门官方网站|中央重点新闻媒体|经人工确认的机构网站/)).toBeVisible();
    await page.getByRole("button", { name: "导入并开始处理" }).click();
    await expect(page).toHaveURL(/\/documents\/\d+\/process/);
    const documentId = Number(page.url().match(/documents\/(\d+)/)?.[1]);
    expect(documentId).toBeGreaterThan(0);
    await expect(page.getByRole("link", { name: /进入对照审核/ })).toBeVisible({ timeout: 180_000 });
    await page.getByRole("link", { name: /进入对照审核/ }).click();
    await expect(page.getByRole("heading", { name: "原文对照审核" })).toBeVisible();
    const fieldCards = page.locator(".review-fields article");
    for (let index = 0; index < await fieldCards.count(); index += 1) {
      const card = fieldCards.nth(index);
      const confirm = card.getByRole("button", { name: /确认/ });
      if (await confirm.isEnabled()) await confirm.click();
    }
    const defaultCover = page.getByRole("button", { name: "使用分类默认图" });
    if (await defaultCover.isVisible()) await defaultCover.click();
    await page.getByRole("button", { name: "完成字段审核" }).click();
    await expect(page).toHaveURL(new RegExp(`/documents/${documentId}/publish`));
    await page.getByRole("button", { name: /确认发布/ }).click();
    await expect(page.getByText(/发布成功/)).toBeVisible();
    await page.goto(h5Url);
    await expect(page.getByText(title).first()).toBeVisible();
    await page.goto(`${h5Url}/news/news-${documentId}`);
    await expect(page.getByRole("button", { name: "查看官方原文" })).toBeVisible();
  });
});
