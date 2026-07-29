import { devices, expect, test, type BrowserContext } from "@playwright/test";
import { buildTelephoneHref } from "../../apps/user-h5/src/utils/contactActions";

const h5Url = "http://127.0.0.1";

function item(
  slug: string,
  title: string,
  fields: Array<{ field_type: string; field_value: string }>,
) {
  return {
    code: 0,
    message: "ok",
    data: {
      id: slug === "health-dynamic" ? 101 : 102,
      slug,
      title,
      summary: "请按通知要求办理。",
      category: "生活服务",
      source_name: "浦江街道社区服务中心",
      published_at: "2026-07-26T00:00:00",
      page_count: 2,
      fields,
      generated: {
        SUMMARY: ["请查看适用对象、时间、地点和所需材料。"],
        STEP_CARDS: [
          { order: 1, title: "准备材料", description: "按页面清单准备。" },
        ],
        RISK_WARNING: [],
        TERM_EXPLANATION: {},
      },
    },
  };
}

async function mockDetail(
  context: BrowserContext,
  slug: string,
  payload: ReturnType<typeof item>,
) {
  await context.route(`**/api/public/items/${slug}/neighbors**`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify({
        code: 0,
        message: "ok",
        data: { previous: null, next: null },
      }),
    }),
  );
  await context.route(`**/api/public/items/${slug}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify(payload),
    }),
  );
}

test("telephone href keeps the real callable number", () => {
  expect(buildTelephoneHref("021-5688-2036")).toBe("tel:021-5688-2036");
  expect(buildTelephoneHref("咨询：021-5688-1026（工作日）")).toBe(
    "tel:021-5688-1026",
  );
  expect(buildTelephoneHref("待人工填写")).toBe("");
});

test("desktop health detail renders only its extracted fields", async ({
  page,
  context,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await mockDetail(
    context,
    "health-dynamic",
    item("health-dynamic", "2026年度老年人免费健康体检预约通知", [
      { field_type: "TARGET_AUDIENCE", field_value: "65周岁及以上常住居民" },
      { field_type: "ELIGIBILITY", field_value: "未参加本年度同类免费体检" },
      { field_type: "START_DATE", field_value: "2026年8月10日" },
      { field_type: "END_DATE", field_value: "2026年8月25日" },
      {
        field_type: "LOCATION",
        field_value: "浦江街道社区卫生服务中心体检大厅",
      },
      { field_type: "CONTACT", field_value: "021-5688-2036" },
      { field_type: "FEE", field_value: "免费" },
      { field_type: "MATERIAL", field_value: "身份证、医保卡、用药清单" },
      { field_type: "WARNING", field_value: "体检当天请空腹" },
    ]),
  );
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${h5Url}/guide/health-dynamic`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.locator("#app")).not.toBeEmpty();
  await expect(page.getByText("65周岁及以上常住居民")).toBeVisible();
  await expect(page.getByText("浦江街道社区卫生服务中心体检大厅")).toBeVisible();
  await expect(page.getByRole("link", { name: "021-5688-2036" })).toHaveAttribute(
    "href",
    "tel:021-5688-2036",
  );
  await expect(page.getByText("体检当天请空腹")).toBeVisible();
  await expect(page.locator(".detail-page")).not.toContainText("80周岁");
  await expect(page.locator(".detail-page")).not.toContainText("021-12345");
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await page.screenshot({
    path: "D:/Temp/jianda-health-detail-dynamic.png",
    fullPage: false,
  });
  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test("Android Chrome silver activity supports LAN HTTP address fallback", async ({
  browser,
}) => {
  const context = await browser.newContext({ ...devices["Pixel 7"] });
  await context.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });
    document.execCommand = () => true;
  });
  await mockDetail(
    context,
    "silver-dynamic",
    item("silver-dynamic", "银龄数字生活公益辅导活动报名通知", [
      { field_type: "TARGET_AUDIENCE", field_value: "55周岁及以上常住居民" },
      { field_type: "START_DATE", field_value: "2026年8月1日" },
      { field_type: "END_DATE", field_value: "2026年8月15日" },
      {
        field_type: "LOCATION",
        field_value: "浦江街道社区服务中心201教室",
      },
      { field_type: "CONTACT", field_value: "021-5688-1026" },
      { field_type: "FEE", field_value: "免费" },
      { field_type: "MATERIAL", field_value: "智能手机、充电线、身份证" },
      {
        field_type: "WARNING",
        field_value: "不提供银行卡、支付密码或短信验证码",
      },
    ]),
  );
  const page = await context.newPage();
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.goto(`${h5Url}/guide/silver-dynamic`);
  await expect(page.getByText("55周岁及以上常住居民")).toBeVisible();
  await expect(page.getByText("浦江街道社区服务中心201教室")).toBeVisible();
  await expect(page.getByRole("link", { name: "021-5688-1026" })).toHaveAttribute(
    "href",
    "tel:021-5688-1026",
  );
  await page.getByRole("button", { name: "复制地址" }).click();
  await expect(page.getByRole("status")).toHaveText("地址已复制");
  await expect(page.locator(".detail-page")).not.toContainText("80周岁");
  await expect(page.locator(".detail-page")).not.toContainText("021-12345");
  await page.screenshot({
    path: "D:/Temp/jianda-silver-detail-android.png",
    fullPage: false,
  });
  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
  await context.close();
});

test("missing fields do not render fabricated cards", async ({
  page,
  context,
}) => {
  await mockDetail(
    context,
    "missing-fields",
    item("missing-fields", "缺失字段材料", []),
  );
  await page.goto(`${h5Url}/guide/missing-fields`);
  await expect(page.getByRole("heading", { name: "缺失字段材料" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "我是否符合条件？" })).toHaveCount(0);
  await expect(page.locator(".quick-info")).toHaveCount(0);
  await expect(page.locator(".material-list")).toHaveCount(0);
  await expect(page.locator(".detail-page")).not.toContainText("80周岁");
  await expect(page.locator(".detail-page")).not.toContainText("021-12345");
  await expect(page.getByRole("button", { name: "复制地址" })).toHaveCount(0);
});
