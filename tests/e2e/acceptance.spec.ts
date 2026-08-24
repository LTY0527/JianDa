import { expect, test, type Page, type TestInfo } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { acceptanceArtifactPath } from "./support/acceptanceArtifacts";

const institutionUrl =
  process.env.JIANDA_INSTITUTION_TEST_URL ?? "http://127.0.0.1:5173";
const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1:5174";

type PublishedItem = {
  slug: string;
  title: string;
  content_kind?: string;
};

async function currentPublishedItem(page: Page): Promise<PublishedItem> {
  const response = await page.request.get(
    `${h5Url}/api/public/items?regionCode=310113102`,
  );
  expect(response.ok()).toBeTruthy();
  const payload = (await response.json()) as { data?: PublishedItem[] };
  const item = payload.data?.[0];
  expect(item, "需要至少一篇当前已发布内容完成验收").toBeTruthy();
  return item!;
}

function detailRoute(item: PublishedItem): string {
  return `/${item.content_kind === "SERVICE_NOTICE" ? "guide" : "news"}/${item.slug}`;
}

async function login(page: Page) {
  await page.goto(`${institutionUrl}/login`);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill("platform_admin");
  await page.getByLabel("密码").fill("Jianda@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(`${institutionUrl}/`);
}

async function assertRendered(page: Page, heading: string | RegExp) {
  await expect(page.locator("body")).not.toBeEmpty();
  await expect(page.getByRole("heading", { name: heading }).first()).toBeVisible();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
}

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  await page.evaluate(() => window.scrollTo(0, 0));
  const viewport = page.viewportSize() ?? { width: 1280, height: 720 };
  await page.screenshot({
    path: acceptanceArtifactPath(testInfo, name, viewport),
    fullPage: false,
  });
}

test.describe("Phase 7.3 rendered acceptance", () => {
  test("用户端五个一级页面在 375px 可阅读且固定导航不遮挡结尾", async ({ page }, testInfo) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.setViewportSize({ width: 375, height: 812 });
    const pages = [
      ["/", "下午好", "h5-home-375.png"],
      ["/news", "权威资讯", "h5-news-375.png"],
      ["/assistant", "简达助手", "h5-assistant-375.png"],
      ["/services", "办事行动中心", "h5-services-375.png"],
      ["/profile", "游客浏览", "h5-profile-375.png"],
    ] as const;

    for (const [route, heading, file] of pages) {
      await page.goto(`${h5Url}${route}`);
      await assertRendered(page, route === "/" ? "推荐内容" : heading);
      const navigation = page.getByRole("navigation", { name: "主要导航" });
      await expect(navigation.getByRole("link")).toHaveCount(5);
      await page.waitForLoadState("networkidle");
      await expect
        .poll(async () => {
          await page.evaluate(() =>
            window.scrollTo(0, document.documentElement.scrollHeight),
          );
          await page.waitForTimeout(100);
          return page.evaluate(() => {
            const nav = document.querySelector(".bottom-nav")?.getBoundingClientRect();
            const main = document.querySelector("main");
            const last = main?.lastElementChild?.getBoundingClientRect();
            return nav && last ? last.bottom > nav.top + 1 : false;
          });
        }, { message: `${route} 最后一块内容被底部导航遮挡` })
        .toBeFalsy();
      await screenshot(page, testInfo, file);
    }
    expect(consoleErrors).toEqual([]);
  });

  test("用户端详情、平板和桌面视口可阅读", async ({ page }, testInfo) => {
    const response = await page.request.get(`${h5Url}/api/public/items`);
    expect(response.ok()).toBeTruthy();
    const payload = await response.json() as { data?: Array<{ slug: string; title: string; content_kind?: string }> };
    const items = payload.data ?? [];
    const guide = items.find((item) => item.content_kind === "SERVICE_NOTICE");
    const news = items.find((item) => item.content_kind !== "SERVICE_NOTICE");
    const firstDetail = guide ?? news ?? items[0];
    const secondDetail = news ?? guide ?? items[0];
    expect(firstDetail, "需要至少一篇已发布内容验证详情页").toBeTruthy();
    expect(secondDetail, "需要至少一篇已发布内容验证资讯详情页").toBeTruthy();
    const detailRoute = (item: { slug: string; content_kind?: string }) =>
      `/${item.content_kind === "SERVICE_NOTICE" ? "guide" : "news"}/${item.slug}`;
    const displayTitle = (title: string) => title.replace(/[-—－][^-—－]+$/, "").trim();
    const checks = [
      [375, 812, detailRoute(firstDetail!), displayTitle(firstDetail!.title), "h5-guide-detail-375.png"],
      [375, 812, detailRoute(secondDetail!), displayTitle(secondDetail!.title), "h5-news-detail-375.png"],
      [768, 1024, "/", "推荐内容", "h5-home-768.png"],
      [1440, 900, "/", "推荐内容", "h5-home-1440.png"],
    ] as const;
    for (const [width, height, route, heading, file] of checks) {
      await page.setViewportSize({ width, height });
      await page.goto(`${h5Url}${route}`);
      await assertRendered(page, heading);
      await screenshot(page, testInfo, file);
    }
  });

  test("用户端核心触控目标至少 48px", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    for (const route of ["/news", "/assistant", "/favorites"]) {
      await page.goto(`${h5Url}${route}`);
      const undersized = await page.evaluate(() => {
        const selectors = [
          ".bottom-nav a",
          ".channel-tabs button",
          ".filter-tabs button",
          ".assistant-session-bar a",
          ".assistant-session-bar button",
        ];
        return [...document.querySelectorAll<HTMLElement>(selectors.join(","))]
          .filter((element) => element.offsetParent !== null)
          .map((element) => ({
            text: element.getAttribute("aria-label") || element.textContent?.trim() || element.tagName,
            width: Math.round(element.getBoundingClientRect().width),
            height: Math.round(element.getBoundingClientRect().height),
          }))
          .filter((item) => item.height < 48 || item.width < 48);
      });
      expect(undersized, `${route} 存在小于48px的核心触控目标`).toEqual([]);
    }
  });

  test("查看原文下载按钮会产生可保存的文本文件", async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 375, height: 812 });
    const item = await currentPublishedItem(page);
    await page.goto(`${h5Url}/original/${item.slug}`);
    await assertRendered(page, "提取文本");
    await screenshot(page, testInfo, "h5-original-375.png");
    await page.evaluate(() => {
      const original = URL.createObjectURL.bind(URL);
      URL.createObjectURL = (object: Blob | MediaSource) => {
        if (object instanceof Blob) {
          (window as typeof window & { __downloadMime?: string }).__downloadMime =
            object.type;
        }
        return original(object);
      };
    });
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", { name: "下载原文" }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.txt$/);
    expect(
      await page.evaluate(
        () => (window as typeof window & { __downloadMime?: string }).__downloadMime,
      ),
    ).toBe("text/plain;charset=utf-8");
    const downloadPath = path.join(os.tmpdir(), download.suggestedFilename());
    await download.saveAs(downloadPath);
    const content = fs.readFileSync(downloadPath);
    expect(content.byteLength).toBeGreaterThan(100);
    expect(content.toString("utf8")).toContain(item.title.split(/[-—－]/)[0].trim());
    await expect(page.getByRole("status")).toContainText("原文已开始下载");
    await screenshot(page, testInfo, "h5-original-download-success-375.png");
  });

  test("详情和原文直接访问时使用正确的站内安全返回", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    const item = await currentPublishedItem(page);
    const itemDetailRoute = detailRoute(item);
    const cases = [
      [itemDetailRoute, itemDetailRoute.startsWith("/guide/") ? "/services" : "/news"],
      [`/original/${item.slug}`, itemDetailRoute],
    ] as const;
    for (const [route, expected] of cases) {
      await page.goto(`${h5Url}${route}`);
      await expect(page.getByRole("button", { name: "返回" })).toBeVisible();
      if (route.startsWith("/original/")) {
        await expect(page.getByRole("heading", { name: "提取文本" }).first()).toBeVisible();
      }
      await page.getByRole("button", { name: "返回" }).click();
      await expect(page).toHaveURL(`${h5Url}${expected}`);
    }
  });

  test("收藏、历史和阅读偏好在本机持久化且清除前确认", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    const item = await currentPublishedItem(page);
    await page.goto(`${h5Url}${detailRoute(item)}`);
    await page.getByRole("button", { name: "收藏", exact: true }).click();
    await expect(page.getByRole("button", { name: "已收藏", exact: true })).toBeVisible();

    await page.goto(`${h5Url}/settings`);
    for (const size of ["18", "20", "22", "24"]) {
      await page.getByRole("button", { name: size, exact: true }).click();
      expect(await page.evaluate(() => localStorage.getItem("jianda_font"))).toBe(size);
    }
    await page.getByRole("button", { name: "较快" }).click();
    await page.getByRole("checkbox").first().focus();
    await page.keyboard.press("Space");
    await page.getByRole("checkbox").nth(2).focus();
    await page.keyboard.press("Space");
    await page.getByRole("button", { name: "文化", exact: true }).click();
    const savedPreferences = await page.evaluate(() => ({
      rate: localStorage.getItem("jianda_rate"),
      contrast: localStorage.getItem("jianda_contrast"),
      reader: localStorage.getItem("jianda_reader_preferences"),
    }));
    await page.reload();
    await expect(page.getByText("当前 24 像素")).toBeVisible();
    await expect(page.getByRole("button", { name: "较快" })).toHaveClass(/active/);
    await expect(page.getByRole("checkbox").first()).toBeChecked();
    await expect(page.getByRole("checkbox").nth(2)).not.toBeChecked();
    expect(savedPreferences.rate).toBe("1.2");
    expect(savedPreferences.contrast).toBe("1");
    expect(savedPreferences.reader).toBeTruthy();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();

    await page.goto(`${h5Url}/profile`);
    await expect(page.getByRole("link", { name: /我的收藏/ })).toContainText("1 条");
    await expect(page.getByRole("link", { name: /历史浏览/ })).toContainText("1 条");
    page.once("dialog", (dialog) => dialog.dismiss());
    await page.getByRole("button", { name: "清除本机收藏和历史" }).click();
    await expect(page.getByRole("link", { name: /我的收藏/ })).toContainText("1 条");
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "清除本机收藏和历史" }).click();
    await expect(page.getByRole("link", { name: /我的收藏/ })).toContainText("0 条");
    await expect(page.getByRole("link", { name: /历史浏览/ })).toContainText("0 条");
  });

  test("网络失败、无结果、内容不存在和助手失败均显示中文降级状态", async ({
    page,
  }) => {
    await page.route("**/api/public/items?*", (route) => route.abort("timedout"));
    await page.goto(h5Url);
    await expect(page.getByRole("status")).toContainText("内容暂时没有加载成功");
    await expect(page.getByRole("button", { name: "重新加载" })).toBeVisible();
    await page.unroute("**/api/public/items?*");

    await page.goto(`${h5Url}/news`);
    await page.getByPlaceholder("搜索标题、摘要或来源").fill("不存在的验收关键词");
    await expect(page.getByText("没有符合条件的资讯")).toBeVisible();

    await page.goto(`${h5Url}/guide/not-found-for-phase-7-3`);
    await expect(page.getByText("这条内容当前无法查看")).toBeVisible();
    await expect(page.getByRole("link", { name: /返回首页/ })).toBeVisible();

    await page.goto(`${h5Url}/original/not-found-for-phase-7-3`);
    await expect(page.getByText("原文暂时无法读取")).toBeVisible();
    await expect(page.getByRole("button", { name: "下载原文" })).toBeDisabled();

    await page.goto(`${h5Url}/assistant`);
    await page.route("**/api/public/assistant/chat", (route) => route.abort("timedout"));
    await page.getByLabel("输入您想了解的问题").fill("网络超时验收问题");
    await page.getByRole("button", { name: "发送问题" }).click();
    await expect(page.getByText(/网络连接失败，请检查当前网络后重新发送/)).toBeVisible();
  });

  test("语音不可用时有文字提示，键盘焦点清晰可见", async ({
    context,
    page,
  }) => {
    await context.addInitScript(() => {
      Object.defineProperty(window, "speechSynthesis", {
        configurable: true,
        value: undefined,
      });
      Object.defineProperty(window, "SpeechSynthesisUtterance", {
        configurable: true,
        value: undefined,
      });
    });
    await page.setViewportSize({ width: 375, height: 812 });
    const item = await currentPublishedItem(page);
    await page.goto(`${h5Url}${detailRoute(item)}`);
    await page.getByRole("button", { name: "听全文", exact: true }).click();
    await expect(page.getByText(/当前浏览器不支持语音播报/)).toBeVisible();
    await page.keyboard.press("Tab");
    const focus = await page.evaluate(() => {
      const element = document.activeElement as HTMLElement;
      const style = getComputedStyle(element);
      return {
        tag: element.tagName,
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
      };
    });
    expect(["A", "BUTTON", "INPUT"]).toContain(focus.tag);
    expect(focus.outlineStyle).toBe("solid");
    expect(focus.outlineWidth).toBe("3px");
  });

  test("机构端主要验收页面可访问并截图", async ({ page }, testInfo) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/login`);
    await assertRendered(page, "让公共服务信息 更清楚、更好办");
    await screenshot(page, testInfo, "admin-login-1440.png");
    await login(page);

    const pages = [
      ["/", /好，平台管理员/, "admin-dashboard-1440.png"],
      ["/documents", "内容中心", "admin-documents-1440.png"],
      ["/public-sources", "采集与来源", "admin-public-sources-1440.png"],
      ["/public-import", "添加内容", "admin-public-import-1440.png"],
      ["/logs", "操作日志", "admin-logs-1440.png"],
    ] as const;
    for (const [route, heading, file] of pages) {
      await page.goto(`${institutionUrl}${route}`);
      await assertRendered(page, heading);
      await screenshot(page, testInfo, file);
    }

    const token = await page.evaluate(() => localStorage.getItem("jianda_token"));
    const item = await currentPublishedItem(page);
    const publicDetail = await page.request.get(`http://127.0.0.1:8080/api/public/items/${item.slug}`);
    const documentId = (await publicDetail.json()).data.document_id;
    await page.goto(`${institutionUrl}/documents/${documentId}/review`);
    await assertRendered(page, "原文对照审核");
    await screenshot(page, testInfo, "admin-review-1440.png");
    expect(token).toBeTruthy();
    expect(consoleErrors).toEqual([]);
  });

  test("机构端权限、真实日志和关键图标按钮具备可读名称", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/login`);
    await page.getByRole("textbox", { name: "账号", exact: true }).fill("wrong_user");
    await page.getByLabel("密码").fill("wrong_password");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByText(/账号或密码错误/)).toBeVisible();

    await login(page);
    await page.goto(`${institutionUrl}/logs`);
    const token = await page.evaluate(() => localStorage.getItem("jianda_token"));
    const response = await page.request.get(
      "http://127.0.0.1:8080/api/operation-logs",
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(response.ok()).toBeTruthy();
    const logs = (await response.json()).data;
    expect(logs.length).toBeGreaterThan(0);
    await expect(page.locator("tbody tr")).toHaveCount(logs.length);

    await page.goto(`${institutionUrl}/documents/upload`);
    await page.locator('input[type="file"]').setInputFiles({
      name: "验收图片.png",
      mimeType: "image/png",
      buffer: Buffer.from("phase-7-3"),
    });
    await expect(
      page.getByRole("button", { name: "移除已选文件" }),
    ).toBeVisible();

    await page.goto(`${institutionUrl}/public-import`);
    await page.locator(".import-history tbody tr").first().getByRole("button", { name: "预览" }).click();
    await expect(
      page.getByRole("button", { name: "关闭原文预览" }),
    ).toBeVisible();

    await page.keyboard.press("Tab");
    const focus = await page.evaluate(() => {
      const style = getComputedStyle(document.activeElement as Element);
      return { style: style.outlineStyle, width: style.outlineWidth };
    });
    expect(focus.style).toBe("solid");
    expect(focus.width).toBe("3px");
  });
});
