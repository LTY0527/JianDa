import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";

const news = {
  id: 901,
  slug: "news-901",
  title: "### 老年人夏季健康提醒 - 新华网",
  summary: "**高温天气**要及时补水，出现持续不适时应咨询医疗机构。".repeat(5),
  category: "健康",
  source_name: "新华网",
  source_url: "https://www.news.cn/example/c.html",
  canonical_url: "https://www.news.cn/example/c.html",
  published_at: "2026-07-28T10:00:00+08:00",
  original_published_at: "2026-07-26T08:00:00+08:00",
  content_kind: "HEALTH_EDUCATION",
  cover_image_type: "CATEGORY_DEFAULT",
  original_file_available: false,
  source_type: "WEB_ARTICLE",
  reading_minutes: 3,
  generated: {
    SUMMARY: ["天气炎热时要及时补水。", "老年人需要特别留意身体变化。", "持续不适时应咨询医疗机构。"],
    ACCESSIBLE_TEXT: "### 与我有关\n**高温天气**会增加身体负担。",
    WHY_IT_MATTERS: ["高温天气会增加老年人的身体负担。"],
    ACTION_CHECKLIST: [{
      action: "及时补水",
      priority: "立即",
      source_quote: "高温天气要及时补水",
      segment_id: 1,
    }],
    KEY_FACTS: [{
      label: "重点",
      value: "持续不适时咨询医疗机构",
      source_quote: "出现持续不适时应咨询医疗机构",
      segment_id: 1,
    }],
    COMMON_MISTAKES: ["不要等到明显口渴才补水。"],
    FAQ: [{
      question: "不舒服怎么办？",
      answer: "持续不适时咨询医疗机构。",
      source_quote: "出现持续不适时应咨询医疗机构",
    }],
    CONTENT_SCOPE: {
      national_or_local: "全国",
      applicable_region: "全国",
      needs_personal_action: true,
    },
    UNCERTAINTIES: ["原文未说明具体饮水量。"],
    RISK_WARNING: ["本文不能替代医生诊疗。"],
    TERM_EXPLANATION: { 高温: "气温较高的天气情况。" },
  },
  fields: [],
};

function api(data: unknown) {
  return {
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ code: 0, message: "成功", data }),
  };
}

test("首页清理技术文本并保持紧凑资讯布局", async ({ page }) => {
  await page.route("**/api/public/items", (route) =>
    route.fulfill(api([news, { ...news, id: 902, slug: "news-902", title: "社区服务消息", category: "社区服务" }])),
  );
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(h5Url);
  await expect(page.getByRole("heading", { name: "今日推荐" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "老年人夏季健康提醒" }).first()).toBeVisible();
  await expect(page.getByText(/###|\*\*/)).toHaveCount(0);
  const order = await page.locator("main section, main header").evaluateAll((nodes) =>
    nodes.map((node) => node.textContent || "").filter((text) =>
      ["今日推荐", "重要提醒", "图文资讯", "重要公共服务通知", "按分类查看"]
        .some((label) => text.includes(label))),
  );
  expect(order.join("|")).toMatch(/今日推荐.*重要提醒.*图文资讯.*重要公共服务通知.*按分类查看/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-product-home-mobile.png"),
    fullPage: false,
  });
});

test("网页资讯快速看懂和完整解读可切换且不显示原 PDF", async ({ page }) => {
  await page.route("**/api/public/items/news-901", (route) =>
    route.fulfill(api(news)),
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/news/news-901`);
  await expect(page.getByRole("button", { name: "快速看懂" })).toHaveClass(/active/);
  await expect(page.getByRole("heading", { name: "今天可以做什么？" })).toBeVisible();
  await expect(page.getByText("及时补水", { exact: true })).toBeVisible();
  await expect(page.getByText(/###|\*\*/)).toHaveCount(0);
  await expect(page.getByText(/原PDF|下载原PDF/)).toHaveCount(0);
  await page.getByRole("button", { name: "完整解读" }).click();
  await expect(page.getByRole("heading", { name: "关键事实" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "常见问题" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "尚待确认" })).toBeVisible();
  await expect(page.getByRole("button", { name: "查看官方原文" })).toBeVisible();
  await page.screenshot({
    path: path.join(os.tmpdir(), "jianda-product-detail-complete-mobile.png"),
    fullPage: false,
  });
});
