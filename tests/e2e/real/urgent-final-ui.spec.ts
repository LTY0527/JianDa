import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { authenticateResident } from "../support/residentAuth";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const institutionUrl = process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const backendUrl = process.env.JIANDA_BACKEND_URL ?? "http://127.0.0.1:8080";
const artifactRoot = path.resolve("artifacts/urgent-final-ui-20260831");
const gucunCode = "310113109";

test.describe.configure({ mode: "serial" });
test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

async function adminLogin(page: Page) {
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(
    process.env.REAL_PLATFORM_ADMIN_USERNAME ?? "platform_admin",
  );
  await page.getByLabel("密码").fill(
    process.env.REAL_PLATFORM_ADMIN_PASSWORD ?? "Jianda@123",
  );
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
}

async function waitForProcessing(request: APIRequestContext, documentId: number, token: string) {
  const headers = { Authorization: `Bearer ${token}` };
  for (let attempt = 0; attempt < 72; attempt += 1) {
    const response = await request.get(
      `${backendUrl}/api/documents/${documentId}/processing-snapshot`,
      { headers },
    );
    expect(response.ok()).toBeTruthy();
    const snapshot = (await response.json()).data as Record<string, unknown>;
    const jobStatus = String(snapshot.jobStatus || "");
    if (["SUCCEEDED", "SUCCESS"].includes(jobStatus)) return headers;
    if (jobStatus.startsWith("FAILED")) {
      throw new Error(`document ${documentId} processing stopped at ${String(snapshot.stage)} (${jobStatus})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2500));
  }
  throw new Error(`document ${documentId} processing did not finish within the acceptance timeout`);
}

test("REAL 390x844 首页真实图、墨绿气泡与一次返回", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await authenticateResident(page, h5Url);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/?region=310113102`, { waitUntil: "networkidle" });
  await expect(page.locator(".commercial-hero")).toBeVisible();
  const heroImage = page.locator(".commercial-hero > img");
  await expect(heroImage).toBeVisible();
  const heroSource = await heroImage.getAttribute("src");
  expect(heroSource).toBeTruthy();
  expect(heroSource).not.toContain("/images/defaults/");
  await expect.poll(() => heroImage.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0 && image.naturalHeight > 0)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await page.locator(".home-search > span").evaluate((node) => getComputedStyle(node).whiteSpace)).toBe("nowrap");
  const assistantBubble = page.locator(".bottom-nav__primary .bottom-nav__icon");
  const bubbleBox = await assistantBubble.boundingBox();
  expect(bubbleBox?.width).toBeGreaterThanOrEqual(58);
  expect(bubbleBox?.width).toBeLessThanOrEqual(62);
  expect(await assistantBubble.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe("rgb(14, 90, 85)");
  expect(await page.locator(".commercial-hero h1").evaluate((node) => getComputedStyle(node).color)).toBe("rgb(255, 255, 255)");
  expect(await page.locator(".commercial-hero p").evaluate((node) => getComputedStyle(node).color)).toBe("rgb(213, 228, 225)");
  await page.screenshot({ path: path.join(artifactRoot, "01_home_390x844_real_cover_green_theme.png"), fullPage: false });
  await page.screenshot({ path: path.join(artifactRoot, "02_home_390x844_assistant_bubble.png"), fullPage: false });

  await page.locator(".commercial-hero a").click();
  const detailHeading = page.locator("main.reader h1");
  await expect(detailHeading).toBeVisible();
  await expect(detailHeading).not.toHaveText("正在加载…");
  for (let index = 0; index < 4; index += 1) {
    const available = page.locator(".article-neighbors button:not(:disabled)");
    await expect(available.first()).toBeVisible();
    const before = page.url();
    const beforeHeading = (await detailHeading.textContent())?.trim() || "";
    await expect.poll(async () => {
      const titles = (await available.locator("b").allTextContents()).map((value) => value.trim());
      return titles.some((value) => value && value !== beforeHeading);
    }).toBe(true);
    const neighborTitles = (await available.locator("b").allTextContents()).map((value) => value.trim());
    const targetIndex = neighborTitles.findIndex((value) => value && value !== beforeHeading);
    const target = available.nth(targetIndex);
    await target.click();
    await expect.poll(() => page.url()).not.toBe(before);
    await expect(detailHeading).not.toHaveText("正在加载…");
    await expect(detailHeading).not.toHaveText(beforeHeading);
    await expect(page.locator(".article-neighbors button:not(:disabled)").first()).toBeVisible();
  }
  await page.screenshot({ path: path.join(artifactRoot, "03_detail_after_swipes_before_back.png"), fullPage: false });
  await page.getByRole("button", { name: "返回" }).click();
  await expect.poll(() => new URL(page.url()).pathname).toBe("/");
  await page.screenshot({ path: path.join(artifactRoot, "04_back_once_returns_home.png"), fullPage: false });
  expect(pageErrors).toEqual([]);
});

test("REAL 顾村上传、处理、审核、发布与跨镇隔离", async ({ page, request }) => {
  test.setTimeout(300_000);
  test.skip(process.env.JIANDA_RUN_URGENT_MUTATING_ACCEPTANCE !== "true", "需显式授权写入验收材料");
  const pdfPath = process.env.JIANDA_URGENT_ACCEPTANCE_PDF;
  test.skip(!pdfPath || !fs.existsSync(pdfPath), "需要指定真实公开 PDF 路径");

  await page.setViewportSize({ width: 390, height: 844 });
  await adminLogin(page);
  await page.goto(`${institutionUrl}/documents/upload`, { waitUntil: "networkidle" });
  await expect(page).toHaveURL(`${institutionUrl}/documents/upload`);
  const fileInput = page.locator(".drop-zone input[type=file]");
  await expect(fileInput).toHaveCount(1);
  await fileInput.setInputFiles(pdfPath!);
  await expect(page.locator(".selected-file")).toBeVisible();
  await page.getByLabel("内容来源").fill("简达课程验收公开材料");
  await page.getByRole("button", { name: "顾村镇" }).click();
  await expect(page.getByText("请选择该材料主要服务的街镇")).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "05_upload_region_selector.png"), fullPage: false });
  await page.getByRole("button", { name: "上传并开始处理" }).click();
  await expect(page).toHaveURL(/\/documents\/\d+\/process/, { timeout: 60_000 });
  const documentId = Number(page.url().match(/\/documents\/(\d+)\/process/)?.[1]);
  expect(documentId).toBeGreaterThan(0);
  const token = await page.evaluate(() => localStorage.getItem("jianda_token") || "");
  expect(token).toBeTruthy();
  const headers = await waitForProcessing(request, documentId, token);

  const detailResponse = await request.get(`${backendUrl}/api/documents/${documentId}`, { headers });
  const detail = (await detailResponse.json()).data as Record<string, unknown>;
  expect(detail.local_scope).toBe("LOCAL_TOWN");
  expect(detail.region_code).toBe(gucunCode);
  expect(detail.street_or_town).toBe("顾村镇");
  const fieldsResponse = await request.get(`${backendUrl}/api/documents/${documentId}/fields`, { headers });
  const fields = (await fieldsResponse.json()).data as Array<{ id: number; field_value: string; review_status: string }>;
  for (const field of fields.filter((item) => item.review_status === "PENDING")) {
    const confirmed = await request.put(`${backendUrl}/api/documents/${documentId}/fields/${field.id}`, {
      headers,
      data: { value: field.field_value, confirmed: true },
    });
    expect(confirmed.ok()).toBeTruthy();
  }
  expect((await request.post(`${backendUrl}/api/documents/${documentId}/review`, {
    headers,
    data: { comment: "验收前顾村地区链路自动复核" },
  })).ok()).toBeTruthy();
  const published = await request.post(`${backendUrl}/api/documents/${documentId}/publish`, {
    headers,
    data: {
      title: String(detail.title),
      category: String(detail.category || "社区服务"),
      sourceName: String(detail.source_name || "简达课程验收公开材料"),
      sourceUrl: "",
      allowPublicOriginal: false,
      publishChannel: "COMMUNITY",
      promoteToRecommend: false,
      importanceLevel: "NORMAL",
    },
  });
  expect(published.ok()).toBeTruthy();
  const slug = String((await published.json()).data.slug);
  for (const [regionCode, visible] of [[gucunCode, true], ["310113102", false], ["310113112", false]] as const) {
    const response = await request.get(`${backendUrl}/api/public/items?regionCode=${regionCode}`);
    const slugs = ((await response.json()).data as Array<{ slug: string }>).map((item) => item.slug);
    expect(slugs.includes(slug)).toBe(visible);
  }

  await authenticateResident(page, h5Url);
  await page.goto(`${h5Url}/?region=${gucunCode}`, { waitUntil: "networkidle" });
  await expect(page.getByText(/宝山区 · 顾村镇/).first()).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "06_gucun_home_after_region_publish.png"), fullPage: false });
});

test("REAL 390x844 顾村发布结果首页复核", async ({ page }) => {
  await authenticateResident(page, h5Url);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/?region=${gucunCode}`, { waitUntil: "networkidle" });
  await expect(page.getByText(/宝山区 · 顾村镇/).first()).toBeVisible();
  const hero = page.locator(".commercial-hero");
  await expect(hero).toBeVisible();
  expect(await hero.locator("h1").evaluate((node) => getComputedStyle(node).color)).toBe("rgb(255, 255, 255)");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: path.join(artifactRoot, "06_gucun_home_after_region_publish.png"), fullPage: false });
});
