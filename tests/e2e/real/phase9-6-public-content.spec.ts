import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const backendUrl = process.env.JIANDA_BACKEND_PROD_URL ?? "http://127.0.0.1:8080";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const platformPassword = process.env.JIANDA_REAL_PLATFORM_PASSWORD;
const enabled = process.env.JIANDA_DOCKER_COMPOSE_UP === "1";
const artifactRoot = path.resolve("artifacts/phase9-6-real-acceptance-ux");

async function assertLoadedImages(page: import("@playwright/test").Page) {
  const images = page.locator("img:visible");
  for (let index = 0; index < await images.count(); index += 1) {
    const image = images.nth(index);
    await expect.poll(() => image.evaluate((node: HTMLImageElement) => ({
      complete: node.complete,
      width: node.naturalWidth,
      height: node.naturalHeight,
    }))).toMatchObject({ complete: true });
    const dimensions = await image.evaluate((node: HTMLImageElement) => ({
      width: node.naturalWidth,
      height: node.naturalHeight,
    }));
    expect(dimensions.width).toBeGreaterThan(0);
    expect(dimensions.height).toBeGreaterThan(0);
  }
}

test.describe("Phase 9.6 无 Mock 公开内容真实验收", () => {
  test.skip(!enabled, "仅在显式启用真实 Docker 验收时运行");

  test("文档 67 的 PDF、追溯字段、审核发布和用户端形成闭环", async ({ page, request }) => {
    test.skip(!platformPassword, "需要通过环境变量提供本地平台演示账号密码");
    fs.mkdirSync(path.join(artifactRoot, "real-e2e"), { recursive: true });
    const login = await request.post(`${backendUrl}/api/auth/login`, {
      data: { username: "platform_admin", password: platformPassword },
    });
    expect(login.ok()).toBeTruthy();
    const token = String((await login.json()).data.token);
    const headers = { Authorization: `Bearer ${token}` };
    const [detailResponse, fieldsResponse, segmentsResponse] = await Promise.all([
      request.get(`${backendUrl}/api/documents/67`, { headers }),
      request.get(`${backendUrl}/api/documents/67/fields`, { headers }),
      request.get(`${backendUrl}/api/documents/67/segments`, { headers }),
    ]);
    const detail = (await detailResponse.json()).data;
    const fields = (await fieldsResponse.json()).data;
    const segments = (await segmentsResponse.json()).data;
    expect(detail.processing_status).toBe("PUBLISHED");
    expect(detail.page_count).toBe(8);
    expect(segments).toHaveLength(8);
    expect(fields).toHaveLength(1);
    expect(fields[0]).toMatchObject({
      field_type: "START_DATE",
      field_label: "实施日期",
      field_value: "2026-09-01",
      review_status: "CONFIRMED",
    });
    const segment = segments.find((item: { id: number }) => item.id === fields[0].segment_id);
    expect(segment.text).toContain(fields[0].source_quote);

    await page.addInitScript((value) => localStorage.setItem("jianda_token", value), token);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/documents/67/review`);
    await expect(page.getByRole("heading", { name: /国家卫生健康标准|医养结合健康管理服务标准/ }).first()).toBeVisible();
    await page.screenshot({ path: path.join(artifactRoot, "real-e2e", "pdf-67-review-1440.png"), fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/guide/guide-67`);
    await expect(page.getByRole("heading", { name: /医养结合健康管理服务标准/ }).first()).toBeVisible();
    await expect(page.getByText("2026-09-01")).toBeVisible();
    await expect(page.getByText("2026-02-28")).toHaveCount(0);
    await assertLoadedImages(page);
    await page.screenshot({ path: path.join(artifactRoot, "real-e2e", "pdf-67-h5-390.png"), fullPage: true });
    const original = await request.get(`${backendUrl}/api/public/items/guide-67/original-file`);
    expect(original.ok()).toBeTruthy();
    expect(original.headers()["content-type"]).toContain("application/pdf");
  });

  test("真实官方封面和四档字号在多视口下无溢出", async ({ page, request }) => {
    const cover = await request.get(`${backendUrl}/api/public/items/news-63/cover`);
    expect(cover.ok()).toBeTruthy();
    expect(cover.headers()["content-type"]).toContain("image/jpeg");
    const viewports = [
      { width: 375, height: 812 },
      { width: 390, height: 844 },
      { width: 768, height: 1024 },
      { width: 1440, height: 900 },
    ];
    const fontSizes = [18, 20, 22, 24];
    fs.mkdirSync(path.join(artifactRoot, "after"), { recursive: true });
    for (let index = 0; index < viewports.length; index += 1) {
      await page.setViewportSize(viewports[index]);
      await page.addInitScript((size) => localStorage.setItem("jianda_font", String(size)), fontSizes[index]);
      await page.goto(`${h5Url}/news/news-63`);
      await expect(page.getByRole("heading", { name: /大场镇2026年政府开放日/ }).first()).toBeVisible();
      await assertLoadedImages(page);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
      await page.screenshot({
        path: path.join(artifactRoot, "after", `h5-news-63-${viewports[index].width}-${fontSizes[index]}px.png`),
        fullPage: true,
      });
    }
  });

  test("搜索无结果可携带原关键词进入简达助手", async ({ page }) => {
    const keyword = "不存在的验收关键词P96";
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/search?q=${encodeURIComponent(keyword)}`);
    await expect(page.getByText("平台资料暂未命中")).toBeVisible();
    await page.getByRole("link", { name: "带关键词问简达" }).click();
    await expect(page).toHaveURL(new RegExp(`/assistant\\?q=${encodeURIComponent(keyword)}`));
    await expect(page.getByPlaceholder(/输入问题/)).toHaveValue(keyword);
  });
});
