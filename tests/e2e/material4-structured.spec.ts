import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";

const item = {
  id: 18,
  slug: "guide-18",
  title: "秋冬季流感疫苗集中接种登记说明",
  category: "健康",
  source_name: "海棠街道社区卫生服务中心",
  published_at: "2026-07-28T00:00:00Z",
  page_count: 1,
  summary: "",
  fields: [
    {
      field_type: "TARGET_AUDIENCE",
      field_value:
        "本街道常住居民中，年满60周岁、目前无急性发热症状且无医生明确告知的接种禁忌者",
    },
    {
      field_type: "ELIGIBILITY",
      field_value:
        "本街道常住居民中，年满60周岁、目前无急性发热症状且无医生明确告知的接种禁忌者",
    },
    { field_type: "MATERIAL", field_value: "本人身份证" },
    {
      field_type: "LOCATION",
      field_value: "海棠街道社区卫生服务中心预防接种门诊",
    },
    { field_type: "CONTACT", field_value: "021-5600-8812" },
    {
      field_type: "WARNING",
      field_value:
        "接种前无需空腹。中心不会通过私人二维码收款，也不会要求提供支付密码。",
    },
  ],
  generated: {
    SUMMARY: [
      "年满60周岁的本街道常住居民可以登记。",
      "旧的模糊场次摘要。",
      "请带本人身份证。",
    ],
    SESSIONS: [
      {
        date: "2026年9月12日",
        time: "08:00-11:30",
        location: "海棠街道社区卫生服务中心预防接种门诊",
      },
      {
        date: "2026年9月13日",
        time: "13:30-16:30",
        location: "海棠街道社区卫生服务中心预防接种门诊",
      },
    ],
    RISK_WARNING: [
      "接种前不需要空腹。",
      "中心不会通过私人二维码向您收款，也不会要求您提供支付密码。",
    ],
    TERM_EXPLANATION: { 预防接种门诊: "就是打疫苗的地方。" },
    STEP_CARDS: [],
  },
};

for (const width of [375, 390, 768, 1440]) {
  test(`material 4 stays structured without overlap at ${width}px`, async ({
    page,
  }) => {
    await page.route("**/api/public/items/guide-18", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ code: 0, data: item }),
      }),
    );
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${h5Url}/guide/guide-18`);
    await expect(page).toHaveTitle(/简达/);
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
    await expect(page.locator(".answer p")).toHaveCount(1);
    await expect(page.locator(".session-list article")).toHaveCount(2);
    await expect(page.getByText("2026年9月12日接种时间为08:00-11:30；2026年9月13日接种时间为13:30-16:30。")).toBeVisible();
    await expect(page.locator(".warm-tip")).toHaveCount(2);
    await expect(page.getByText("接种前不需要空腹。")).toBeVisible();
    await expect(page.getByText(/负责疫苗登记、接种前询问、健康检查/)).toBeVisible();
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(overflow).toBe(false);
    if (width === 375) {
      for (let index = 0; index < 3; index += 1) {
        await page.getByRole("button", { name: /\d+px/ }).last().click();
      }
      await expect(page.locator(".detail-page")).toHaveCSS("--reader-size", "24px");
      await page.screenshot({
        path: path.join(os.tmpdir(), "jianda-material4-375-large.png"),
        fullPage: true,
      });
    }
    if (width === 1440) {
      await page.screenshot({
        path: path.join(os.tmpdir(), "jianda-material4-1440.png"),
        fullPage: true,
      });
    }
  });
}
