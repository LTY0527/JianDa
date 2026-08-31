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

  test("published town context is carried to the H5", () => {
    expect(
      buildH5GuideUrl("guide-14", {
        isDev: false,
        protocol: "http:",
        hostname: "127.0.0.1",
      }, "guide", "310113109"),
    ).toBe("http://127.0.0.1/guide/guide-14?region=310113109");
  });

  test("unsupported shared-region codes are not carried to the H5", () => {
    expect(
      buildH5GuideUrl("guide-14", {
        isDev: false,
        protocol: "http:",
        hostname: "127.0.0.1",
      }, "guide", "100000"),
    ).toBe("http://127.0.0.1/guide/guide-14");
  });
});

test("Docker publish success link opens the real H5 detail", async ({
  page,
  context,
}) => {
  const institutionUrl = "http://127.0.0.1:8090";
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
  const publishedRow = page.locator("tbody tr").first();
  await expect(publishedRow).toBeVisible();
  const publicLink = publishedRow.getByRole("link", { name: "查看" });
  const expectedH5Url = await publicLink.getAttribute("href");
  const parsedH5Url = new URL(expectedH5Url!);
  expect(parsedH5Url.origin).toBe("http://127.0.0.1");
  expect(parsedH5Url.pathname).toMatch(/^\/(guide|news)\/[a-z]+-\d+$/);
  if (parsedH5Url.searchParams.has("region")) {
    expect(["310113102", "310113109", "310113112"]).toContain(parsedH5Url.searchParams.get("region"));
  }
  await page.screenshot({
    path: "D:/Temp/jianda-publish-success-link.png",
    fullPage: false,
  });
  const [publicPage] = await Promise.all([
    context.waitForEvent("page"),
    publicLink.click(),
  ]);
  await publicPage.waitForLoadState("networkidle");
  await expect(publicPage).toHaveURL(expectedH5Url!);
  await expect(publicPage).toHaveTitle(/简达/);
  await expect(publicPage.locator("#app")).not.toBeEmpty();
  await expect(publicPage.locator("main h1").first()).toBeVisible();
  await expect(publicPage.locator("vite-error-overlay")).toHaveCount(0);
  await publicPage.screenshot({
    path: "D:/Temp/jianda-publish-h5-link.png",
    fullPage: false,
  });
  expect(consoleErrors.filter((message) => !message.includes("Failed to load resource"))).toEqual([]);
  expect(pageErrors).toEqual([]);
});
