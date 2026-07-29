import { expect, test, type Page, type Route } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1:5174";

function api(data: unknown) {
  return {
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: 0, message: "成功", data }),
  };
}

function onePagePdf(): Buffer {
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << >> >>",
    "<< /Length 0 >>\nstream\n\nendstream",
  ];
  let body = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((object, index) => {
    offsets.push(Buffer.byteLength(body, "binary"));
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = Buffer.byteLength(body, "binary");
  body += `xref\n0 ${objects.length + 1}\n`;
  body += "0000000000 65535 f \n";
  for (const offset of offsets.slice(1)) {
    body += `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`;
  body += `startxref\n${xref}\n%%EOF`;
  return Buffer.from(body, "binary");
}

async function mockDetail(page: Page, slug: string) {
  await page.route(`**/api/public/items/${slug}`, (route) =>
    route.fulfill(
      api({
        id: 201,
        slug,
        title: "中文文件名养老服务标准",
        source_type: "PDF",
        mime_type: "application/pdf",
        original_filename: "养老服务标准（测试）.pdf",
        original_file_available: true,
      }),
    ),
  );
}

test("Range 路径失败后自动使用完整 PDF Blob", async ({ page }) => {
  const slug = "pdf-blob-fallback";
  await mockDetail(page, slug);
  let fileRequests = 0;
  await page.route(`**/api/public/items/${slug}/original-file`, (route) => {
    fileRequests += 1;
    if (fileRequests === 1) {
      return route.fulfill({ status: 416, body: "" });
    }
    return route.fulfill({
      status: 200,
      contentType: "application/pdf",
      body: onePagePdf(),
    });
  });

  await page.goto(`${h5Url}/original-file/${slug}`);
  const reader = page.getByRole("region", { name: "PDF 在线阅读器" });
  await expect(
    reader.getByText("Range 分段加载失败，已自动切换到完整文件模式。"),
  ).toBeVisible();
  await expect(reader.getByText("第 1 / 1 页")).toBeVisible();
  await expect
    .poll(() => reader.locator("canvas").evaluate((canvas) => canvas.width))
    .toBeGreaterThan(0);
  expect(fileRequests).toBeGreaterThanOrEqual(2);
});

test("登录失效显示明确的 401 诊断", async ({ page }) => {
  const slug = "pdf-auth-expired";
  await mockDetail(page, slug);
  await page.route(`**/api/public/items/${slug}/original-file`, (route) =>
    route.fulfill({ status: 401, contentType: "application/json", body: "{}" }),
  );

  await page.goto(`${h5Url}/original-file/${slug}`);
  await expect(page.getByText("登录状态已失效，请重新登录后再试。")).toBeVisible();
});

test("JSON 错误响应不会被误判为 PDF", async ({ page }) => {
  const slug = "pdf-json-error";
  await mockDetail(page, slug);
  const respondJson = (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 404, message: "文件不存在" }),
    });
  await page.route(
    `**/api/public/items/${slug}/original-file`,
    respondJson,
  );

  await page.goto(`${h5Url}/original-file/${slug}`);
  await expect(
    page.getByText("后端返回了错误信息而不是 PDF 文件，请查看任务或服务日志。"),
  ).toBeVisible();
});
