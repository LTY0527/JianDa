import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";

const materialFive = {
  id: 105,
  slug: "guide-generic-105",
  title: "居民身份证到期换领分时办理提示",
  category: "政务",
  source_name: "虹桥政务服务分中心",
  published_at: "2026-07-28T00:00:00Z",
  page_count: 1,
  mime_type: "application/pdf",
  original_file_available: true,
  fields: [
    {
      field_type: "TARGET_AUDIENCE",
      field_value: "身份证有效期不足三个月的人员",
    },
    {
      field_type: "LOCATION",
      field_value: "虹桥政务服务分中心二楼公安综合窗口（虹桥路188号）",
    },
    { field_type: "CONTACT", field_value: "021-6209-4500" },
  ],
  generated: {
    SUMMARY: ["确认身份证有效期。", "按分时安排到窗口办理。", "按户籍和年龄准备材料。"],
    STEP_CARDS: [
      { order: 1, title: "确认条件", description: "确认身份证有效期。" },
      { order: 2, title: "准备材料", description: "按所属人群准备材料。" },
    ],
    AUDIENCE_RULES: {
      audience: [{ value: "身份证有效期不足三个月的人员" }],
      conditions: [],
    },
    SERVICE_SCHEDULE: {
      service_windows: [
        {
          days: ["周一", "周三"],
          dates: [],
          time_ranges: ["08:30-11:30", "13:30-16:30"],
          location: "二楼公安综合窗口",
        },
        {
          days: ["周六"],
          dates: [],
          time_ranges: ["09:00-11:30"],
          location: "二楼公安综合窗口",
          unavailable_note: "周六下午不开放",
        },
      ],
      closure_rules: [{ value: "法定节假日不受理" }],
    },
    CONDITIONAL_MATERIALS: [
      {
        applicable_to: "本市户籍人员",
        required: ["原居民身份证"],
        optional: [],
      },
      {
        applicable_to: "外省户籍人员",
        required: ["原居民身份证", "本市居住登记凭证"],
        optional: [],
      },
      {
        applicable_to: "未满16周岁人员",
        required: ["监护关系证明材料"],
        optional: [],
      },
    ],
    FEES: [
      {
        fee_type: "到期换领",
        amount: "每证20元",
        payment_methods: ["现金", "移动支付"],
      },
      {
        fee_type: "损坏换领",
        rule: "按窗口公示标准收取",
        payment_methods: ["现金", "移动支付"],
      },
    ],
    RESULT_DELIVERY: [
      {
        method: "窗口领取",
        optional: false,
        available_after: "20个工作日后",
        location: "原窗口",
      },
      {
        method: "邮寄",
        optional: true,
        fee_rule: "邮寄费用由邮政服务单位另行收取",
      },
    ],
    DEADLINE_RULES: [
      {
        rule_type: "RELATIVE_PERIOD",
        value: "收到短信提醒后30日内",
      },
    ],
    AMENDMENTS: [],
    RISK_WARNING: ["不要向个人账户转账。"],
  },
};

for (const width of [375, 768]) {
  test(`generic public-service structures render without overflow at ${width}px`, async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.route("**/api/public/items/guide-generic-105", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ code: 0, data: materialFive }),
      }),
    );
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${h5Url}/guide/guide-generic-105`);

    await expect(page.getByRole("heading", { name: "什么时候能办？" })).toBeVisible();
    await expect(page.locator(".service-window-list article")).toHaveCount(2);
    await expect(page.getByText("周六下午不开放")).toBeVisible();
    await expect(page.getByText("法定节假日不受理")).toBeVisible();
    await expect(page.locator(".conditional-material-card")).toHaveCount(3);
    await expect(page.getByText("每证20元")).toBeVisible();
    await expect(page.getByText("邮寄费用由邮政服务单位另行收取")).toBeVisible();
    await expect(page.getByText("收到短信提醒后30日内")).toBeVisible();
    await expect(page.getByRole("link", { name: /查看原PDF/ })).toBeVisible();

    const overflow = await page.evaluate(
      () =>
        document.documentElement.scrollWidth >
        document.documentElement.clientWidth,
    );
    expect(overflow).toBe(false);
    expect(consoleErrors).toEqual([]);
    expect(pageErrors).toEqual([]);
    if (width === 375) {
      await page.screenshot({
        path: path.join(os.tmpdir(), "jianda-generic-structure-375.png"),
        fullPage: true,
      });
    }
  });
}

test("public original PDF route uses same-origin API and preserves safe back navigation", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.route("**/api/public/items/guide-generic-105", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 0, data: materialFive }),
    }),
  );
  await page.route("**/api/public/items/guide-generic-105/original-file", (route) =>
    route.fulfill({
      contentType: "application/pdf",
      body: Buffer.from("%PDF-1.4\n% test"),
    }),
  );

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(
    `${h5Url}/original-file/guide-generic-105?from=guide`,
  );
  await expect(page.getByText("查看原PDF")).toBeVisible();
  await expect(page.locator(".original-file-page iframe")).toHaveAttribute(
    "src",
    "/api/public/items/guide-generic-105/original-file",
  );
  await page.getByRole("button", { name: "返回" }).click();
  await expect(page).toHaveURL(`${h5Url}/guide/guide-generic-105`);
  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
