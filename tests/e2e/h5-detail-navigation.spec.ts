import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1:5174";

const articles = {
  a: {
    id: 201,
    slug: "navigation-a",
    title: "连续阅读测试文章甲",
    category: "健康",
    summary: "甲文章摘要不会保留到下一篇。",
    uniqueText: "甲文章独有正文",
  },
  b: {
    id: 202,
    slug: "navigation-b",
    title: "连续阅读测试文章乙",
    category: "反诈",
    summary: "乙文章是同分类边界后的全局回退文章。",
    uniqueText: "乙文章独有正文",
  },
};

function neighbor(article: (typeof articles)[keyof typeof articles]) {
  return {
    id: article.id,
    slug: article.slug,
    title: article.title,
    category: article.category,
    content_kind: "HEALTH_EDUCATION",
  };
}

function detail(article: (typeof articles)[keyof typeof articles]) {
  return {
    code: 0,
    message: "成功",
    data: {
      ...article,
      source_name: "测试权威来源",
      source_url: "https://example.gov.cn/article",
      canonical_url: "https://example.gov.cn/article",
      published_at: "2026-07-29T10:00:00",
      original_published_at: "2026-07-29T09:00:00",
      content_kind: "HEALTH_EDUCATION",
      fields: [],
      generated: {
        SUMMARY: [article.summary],
        ACCESSIBLE_TEXT: article.uniqueText,
        WHY_IT_MATTERS: [article.uniqueText],
        FAQ: [],
        RISK_WARNING: [],
        TERM_EXPLANATION: {},
      },
    },
  };
}

async function mockNavigation(context: BrowserContext, detailDelayMs = 0) {
  await context.route("**/api/public/items/**", async (route) => {
    const url = new URL(route.request().url());
    const segments = url.pathname.split("/");
    const slug = segments.at(-1) === "neighbors" ? segments.at(-2) : segments.at(-1);
    const article = slug === articles.b.slug ? articles.b : articles.a;
    const data = segments.at(-1) === "neighbors"
      ? {
          code: 0,
          message: "成功",
          data: article === articles.a
            ? { previous: null, next: neighbor(articles.b) }
            : { previous: neighbor(articles.a), next: null },
        }
      : detail(article);
    if (segments.at(-1) !== "neighbors" && detailDelayMs) {
      await new Promise((resolve) => setTimeout(resolve, detailDelayMs));
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json; charset=utf-8",
      body: JSON.stringify(data),
    });
  });
}

async function expectArticle(page: Page, article: (typeof articles)[keyof typeof articles]) {
  await expect(page).toHaveURL(new RegExp(`/${article.slug}$`));
  await expect(page.getByRole("heading", { level: 1, name: article.title })).toBeVisible();
  await expect(page.getByText(article.uniqueText)).toBeVisible();
}

async function swipe(page: Page, selector: string, fromX: number, toX: number) {
  await page.locator(selector).dispatchEvent("pointerdown", {
    pointerId: 1, pointerType: "touch", isPrimary: true, button: 0,
    clientX: fromX, clientY: 400,
  });
  await page.locator(selector).dispatchEvent("pointerup", {
    pointerId: 1, pointerType: "touch", isPrimary: true, button: 0,
    clientX: toX, clientY: 405,
  });
}

test.beforeEach(async ({ context }) => {
  await mockNavigation(context);
});

test("desktop buttons, keyboard, history and text selection keep article state isolated", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${h5Url}/news/${articles.a.slug}`);
  await expectArticle(page, articles.a);
  await expect(page.getByRole("button", { name: /上一篇/ }).last()).toBeDisabled();
  await expect(page.getByRole("button", { name: /下一篇：连续阅读测试文章乙/ })).toBeVisible();

  const selection = await page.locator(".article-readable p").evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const selected = window.getSelection();
    selected?.removeAllRanges();
    selected?.addRange(range);
    return selected?.toString();
  });
  expect(selection).toContain(articles.a.uniqueText);

  await page.getByRole("button", { name: /下一篇：连续阅读测试文章乙/ }).click();
  await expectArticle(page, articles.b);
  await expect(page.getByText(articles.a.uniqueText)).toHaveCount(0);
  await expect(page.getByRole("button", { name: /下一篇/ }).last()).toBeDisabled();

  await page.goBack();
  await expectArticle(page, articles.a);
  await page.goForward();
  await expectArticle(page, articles.b);
  await page.keyboard.press("ArrowLeft");
  await expectArticle(page, articles.a);

  await page.locator("main").evaluate((main) => {
    const input = document.createElement("input");
    input.setAttribute("aria-label", "测试输入框");
    const editor = document.createElement("div");
    editor.contentEditable = "true";
    editor.setAttribute("role", "textbox");
    editor.setAttribute("aria-label", "测试编辑区");
    main.prepend(input, editor);
  });
  await page.getByRole("textbox", { name: "测试输入框" }).focus();
  await page.keyboard.press("ArrowRight");
  await expectArticle(page, articles.a);
  await page.getByRole("textbox", { name: "测试编辑区" }).focus();
  await page.keyboard.press("ArrowRight");
  await expectArticle(page, articles.a);
  await page.locator("body").focus();
  await page.keyboard.press("Control+ArrowRight");
  await expectArticle(page, articles.a);
});

test("loading a new route clears the previous article before the response arrives", async ({ page, context }) => {
  await context.unroute("**/api/public/items/**");
  await mockNavigation(context, 250);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${h5Url}/news/${articles.a.slug}`);
  await expectArticle(page, articles.a);
  await page.getByRole("button", { name: /下一篇：连续阅读测试文章乙/ }).click();
  await expect(page).toHaveURL(new RegExp(`/${articles.b.slug}$`));
  await expect(page.getByText(articles.a.uniqueText)).toHaveCount(0);
  await expect(page.getByLabel("正在加载详情")).toBeVisible();
  await expectArticle(page, articles.b);
});

test("375px swipe respects interactive controls, vertical movement and pointer cancellation", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`${h5Url}/news/${articles.a.slug}`);
  await expectArticle(page, articles.a);
  await expect(page.locator(".article-side-nav")).toBeHidden();
  await expect(page.locator(".article-side-nav")).toHaveCSS("pointer-events", "none");

  await page.locator("main").evaluate((main) => {
    const host = document.createElement("section");
    host.innerHTML = `
      <audio aria-label="测试音频" controls></audio>
      <video aria-label="测试视频"></video>
      <details><summary>测试详情摘要</summary><p>测试详情内容</p></details>
      <a href="#safe-link">测试链接</a>
      <button type="button">测试按钮</button>
    `;
    main.prepend(host);
  });

  for (const selector of [
    "audio", "video", "summary", "details p", "a[href='#safe-link']", "button:has-text('测试按钮')",
  ]) {
    await swipe(page, selector, 320, 80);
    await expectArticle(page, articles.a);
  }

  await swipe(page, ".article-readable p", 220, 180);
  await expectArticle(page, articles.a);
  await page.locator(".article-readable p").dispatchEvent("pointerdown", {
    pointerId: 2, pointerType: "touch", isPrimary: true, button: 0, clientX: 320, clientY: 300,
  });
  await page.locator(".article-readable p").dispatchEvent("pointercancel", {
    pointerId: 2, pointerType: "touch", isPrimary: true, button: 0, clientX: 240, clientY: 300,
  });
  await page.locator(".article-readable p").dispatchEvent("pointerup", {
    pointerId: 2, pointerType: "touch", isPrimary: true, button: 0, clientX: 60, clientY: 300,
  });
  await expectArticle(page, articles.a);

  await page.locator(".article-readable p").dispatchEvent("pointerdown", {
    pointerId: 3, pointerType: "touch", isPrimary: true, button: 0, clientX: 300, clientY: 250,
  });
  await page.locator(".article-readable p").dispatchEvent("pointerup", {
    pointerId: 3, pointerType: "touch", isPrimary: true, button: 0, clientX: 220, clientY: 500,
  });
  await expectArticle(page, articles.a);

  await swipe(page, ".article-readable p", 330, 70);
  await expectArticle(page, articles.b);
  await swipe(page, ".article-readable p", 70, 330);
  await expectArticle(page, articles.a);
});
