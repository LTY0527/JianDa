import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";

test("真实上传 PDF 可在桌面和移动端内置阅读器中查看与下载", async ({
  page,
  request,
}) => {
  const login = await request.post(`${institutionUrl}/api/auth/login`, {
    data: { username: "platform_admin", password: "Jianda@123" },
  });
  expect(login.ok()).toBeTruthy();
  const token = (await login.json()).data.token as string;
  const documentsResponse = await request.get(`${institutionUrl}/api/documents`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(documentsResponse.ok()).toBeTruthy();
  const documents = (await documentsResponse.json()).data as Array<{
    id: number;
    file_name?: string | null;
    status?: string;
  }>;
  const pdf = documents.find((item) =>
    item.file_name?.toLocaleLowerCase().endsWith(".pdf") &&
    ["WAITING_REVIEW", "REVIEWED", "PUBLISHED"].includes(item.status || ""),
  );
  test.skip(!pdf, "当前数据库没有可用于真实阅读器验收的已上传 PDF");

  await page.addInitScript((value) => {
    localStorage.setItem("jianda_token", value);
  }, token);

  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  for (const viewport of [
    { width: 1440, height: 900, name: "desktop" },
    { width: 390, height: 844, name: "mobile" },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto(`${institutionUrl}/documents/${pdf!.id}/review`);
    await page.getByRole("button", { name: "原PDF" }).click();
    const reader = page.getByRole("region", { name: "PDF 在线阅读器" });
    await expect(reader).toBeVisible();
    await expect
      .poll(() => reader.locator("canvas").evaluate((canvas) => canvas.width))
      .toBeGreaterThan(0);
    await expect(reader.getByText(/第 \d+ \/ \d+ 页/)).toBeVisible();
    const beforeZoom = await reader.locator("canvas").evaluate((canvas) => canvas.style.width);
    await reader.getByRole("button", { name: "放大" }).click();
    await expect
      .poll(() => reader.locator("canvas").evaluate((canvas) => canvas.style.width))
      .not.toBe(beforeZoom);
    await reader.getByRole("button", { name: "适合宽度" }).click();
    await page.screenshot({
      path: path.join(os.tmpdir(), `jianda-live-pdf-${viewport.name}.png`),
      fullPage: false,
    });
  }

  const download = await request.get(
    `${institutionUrl}/api/documents/${pdf!.id}/original-file?download=true`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(download.ok()).toBeTruthy();
  expect(download.headers()["content-type"]).toContain("application/pdf");
  expect(download.headers()["content-disposition"]).toContain("attachment");
  expect(pageErrors).toEqual([]);
  expect(consoleErrors).toEqual([]);
});
