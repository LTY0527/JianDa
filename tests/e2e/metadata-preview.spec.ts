import { expect, test, type Page } from "@playwright/test";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";

async function prepare(page: Page) {
  await page.addInitScript(() => localStorage.setItem("jianda_token", "test-token"));
}

const preview = {
  title: "秋冬季流感疫苗集中接种登记说明",
  source_name: "海棠街道社区卫生服务中心",
  document_number: "海卫预防〔2026〕09号",
  source_type: "基层医疗卫生机构",
  authority_status: "DOCUMENT_EVIDENCE",
  confidence: 0.96,
  evidence_quote: "海棠街道社区卫生服务中心",
  evidence_type: "HEADER",
  page_no: 1,
  warnings: [],
};

test("metadata preview fills title and source but never overwrites manual edits", async ({
  page,
}) => {
  await prepare(page);
  await page.route("**/api/documents/metadata-preview", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 250));
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 0, message: "ok", data: preview }),
    });
  });
  await page.goto(`${institutionUrl}/documents/upload`);
  await page.locator('input[type="file"]').setInputFiles({
    name: "简达_模拟材料4_社区流感疫苗接种登记说明.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-preview"),
  });
  const title = page.getByLabel("材料标题");
  const source = page.getByLabel("内容来源");
  await expect(title).toHaveValue("社区流感疫苗接种登记说明");
  await title.fill("人工确认后的标题");
  await source.fill("人工确认后的来源");
  await expect(page.getByText("正在识别材料标题和发布机构……")).toBeVisible();
  await expect(page.getByText("材料内有发布机构证据")).toBeVisible();
  await expect(title).toHaveValue("人工确认后的标题");
  await expect(source).toHaveValue("人工确认后的来源");
  await expect(page.getByText("海卫预防〔2026〕09号")).toBeVisible();
});

test("preview failure keeps filename title and does not block upload", async ({
  page,
}) => {
  await prepare(page);
  await page.route("**/api/documents/metadata-preview", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      headers: { "X-Request-Id": "safe-request-1" },
      body: JSON.stringify({ code: 503, message: "AI 服务调用失败", data: null }),
    }),
  );
  await page.route("**/api/documents", async (route) => {
    if (route.request().method() === "POST") {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ code: 0, data: { id: 88 } }),
      });
    }
    return route.continue();
  });
  await page.route("**/api/documents/88/**", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 0, data: { status: "WAITING_REVIEW" } }),
    }),
  );
  await page.goto(`${institutionUrl}/documents/upload`);
  await page.locator('input[type="file"]').setInputFiles({
    name: "简达_模拟材料4_社区流感疫苗接种登记说明.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-preview"),
  });
  await expect(page.getByText(/请求编号：safe-request-1/)).toBeVisible();
  await expect(page.getByLabel("材料标题")).toHaveValue("社区流感疫苗接种登记说明");
  await page.getByLabel("内容来源").fill("海棠街道社区卫生服务中心");
  await page.getByRole("button", { name: "上传并开始处理" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/documents/88/process`);
});
