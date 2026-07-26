import { expect, test } from "@playwright/test";
import { buildH5GuideUrl } from "../../apps/institution-web/src/utils/h5-url";

test.describe("institution publish H5 URL", () => {
  test("Docker production omits the institution port", () => {
    expect(
      buildH5GuideUrl("guide-14", {
        isDev: false,
        protocol: "http:",
        hostname: "127.0.0.1",
      }),
    ).toBe("http://127.0.0.1/guide/guide-14");
  });

  test("Vite development defaults to port 5174", () => {
    expect(
      buildH5GuideUrl("guide-14", {
        isDev: true,
        protocol: "http:",
        hostname: "127.0.0.1",
      }),
    ).toBe("http://127.0.0.1:5174/guide/guide-14");
  });

  test("configured HTTPS origin takes priority", () => {
    expect(
      buildH5GuideUrl("guide-14", {
        configuredBaseUrl: "https://service.example.gov.cn:8443/ignored-path",
        isDev: true,
        protocol: "http:",
        hostname: "127.0.0.1",
      }),
    ).toBe("https://service.example.gov.cn:8443/guide/guide-14");
  });
});

test("Docker publish success link opens the real H5 detail", async ({
  page,
  context,
}) => {
  const institutionUrl = "http://127.0.0.1:8090";
  const expectedH5Url = "http://127.0.0.1/guide/guide-14";
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  context.on("page", (openedPage) => {
    openedPage.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    openedPage.on("pageerror", (error) => pageErrors.push(error.message));
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill("org_admin");
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);

  await page.goto(`${institutionUrl}/published`);
  const publishedRow = page
    .getByRole("row")
    .filter({ hasText: "2026年度老年人免费健康体检预约通知" });
  await expect(publishedRow.getByRole("link", { name: "查看" })).toHaveAttribute(
    "href",
    expectedH5Url,
  );

  await page.route("**/api/documents/14/publish", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify({
        code: 0,
        message: "ok",
        data: { slug: "guide-14" },
      }),
    });
  });
  await page.goto(`${institutionUrl}/documents/14/publish`);
  await expect(page.getByRole("heading", { name: "审核与发布" })).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "审核通过并发布" }).click();
  await expect(page.getByRole("heading", { name: "内容已成功发布" })).toBeVisible();

  const publicLink = page.getByRole("link", { name: "打开用户端" });
  await expect(publicLink).toHaveAttribute("href", expectedH5Url);
  await page.screenshot({
    path: "D:/Temp/jianda-publish-success-link.png",
    fullPage: false,
  });
  const [publicPage] = await Promise.all([
    context.waitForEvent("page"),
    publicLink.click(),
  ]);
  await publicPage.waitForLoadState("networkidle");
  await expect(publicPage).toHaveURL(expectedH5Url);
  await expect(publicPage).toHaveTitle(/简达/);
  await expect(publicPage.locator("#app")).not.toBeEmpty();
  await expect(
    publicPage.getByRole("heading", {
      name: "2026年度老年人免费健康体检预约通知",
    }),
  ).toBeVisible();
  await expect(publicPage.locator("vite-error-overlay")).toHaveCount(0);
  await publicPage.screenshot({
    path: "D:/Temp/jianda-publish-h5-link.png",
    fullPage: false,
  });
  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
