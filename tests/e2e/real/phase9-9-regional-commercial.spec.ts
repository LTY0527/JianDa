import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { authenticateResident } from "../support/residentAuth";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const institutionUrl = process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const artifactRoot = path.resolve("artifacts/phase9-9-commercial-regional");

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

test("REAL H5 三地区、五个服务入口与商业边界不依赖 mock", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await authenticateResident(page, h5Url);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "networkidle" });
  await expect(page.locator("#app")).not.toBeEmpty();
  await page.screenshot({ path: path.join(artifactRoot, "h5-home-dachang-390.png"), fullPage: false });

  await page.getByRole("button", { name: "选择所在地区" }).click();
  await expect(page.getByRole("dialog", { name: "选择所在地区" })).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "h5-region-map-390.png"), fullPage: false });
  await page.getByRole("button", { name: /大场镇/ }).last().click();

  for (const region of ["顾村镇", "庙行镇", "大场镇"]) {
    const regionButton = page.getByRole("button", { name: "选择所在地区" });
    await regionButton.click();
    await page.getByRole("button", { name: new RegExp(region) }).last().click();
    await expect(regionButton).toContainText(region);
    await page.waitForLoadState("networkidle");
    await page.screenshot({ path: path.join(artifactRoot, `h5-home-${region === "顾村镇" ? "gucun" : region === "庙行镇" ? "miaohang" : "dachang"}-390.png`), fullPage: false });
  }

  const routes = [
    ["/services", "h5-service-home-390.png"],
    ["/services/health", "h5-health-390.png"],
    ["/services/meals", "h5-meals-390.png"],
    ["/services/contacts", "h5-contacts-390.png"],
    ["/activities", "h5-activities-390.png"],
    ["/services/guides", "h5-guides-390.png"],
  ] as const;
  for (const [route, screenshotName] of routes) {
    await page.goto(`${h5Url}${route}`, { waitUntil: "networkidle" });
    await expect(page).toHaveURL(new RegExp(route.replace("/", "\\/")));
    await expect(page.locator("main")).not.toBeEmpty();
    await page.screenshot({ path: path.join(artifactRoot, screenshotName), fullPage: false });
  }
  await page.goto(`${h5Url}/trusted-services`, { waitUntil: "networkidle" });
  await expect(page.getByText(/合作服务，不是政府办事事项/)).toBeVisible();
  await expect(page.getByText(/平台不会用演示商家补充列表/)).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "h5-commercial-service-390.png"), fullPage: true });
  expect(errors).toEqual([]);
});

test("REAL 平台管理员可查看采集与商业运营真实状态", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill("platform_admin");
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
  await page.goto(`${institutionUrl}/public-sources`, { waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: "采集与来源" })).toBeVisible();
  expect(await page.locator(".source-card").count()).toBeGreaterThanOrEqual(12);
  await page.screenshot({ path: path.join(artifactRoot, "admin-sources-1440.png"), fullPage: false });
  await page.goto(`${institutionUrl}/commercial`, { waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: "商业运营" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "支付配置" })).toBeVisible();
  await expect(page.getByText("线上支付：已配置")).toBeVisible();
  await expect(page.getByText(/支付宝：未接入/)).toBeVisible();
  await expect(page.getByText(/REAL_PAYMENT_PROVIDER_ACCEPTANCE|运营边界/)).toHaveCount(0);
  await page.screenshot({ path: path.join(artifactRoot, "platform-commercial-1440.png"), fullPage: false });
});
