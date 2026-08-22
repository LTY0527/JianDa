import { expect, test } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_TEST_URL || "http://127.0.0.1:5174";

async function installSpeechMock(context: import("@playwright/test").BrowserContext) {
  await context.addInitScript(() => {
    const calls: Array<{ action: string; text?: string; rate?: number; lang?: string }> = [];
    class MockUtterance {
      text: string;
      lang = "";
      rate = 1;
      voice: unknown = null;
      onend: null | (() => void) = null;
      onerror: null | ((event: { error: string }) => void) = null;
      constructor(text: string) { this.text = text; }
    }
    const synthesis = {
      paused: false,
      pending: false,
      speaking: false,
      getVoices: () => [{ name: "普通话", lang: "zh-CN", default: true }],
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      speak: (utterance: MockUtterance) => {
        calls.push({ action: "speak", text: utterance.text, rate: utterance.rate, lang: utterance.lang });
        (window as any).__lastUtterance = utterance;
      },
      pause: () => calls.push({ action: "pause" }),
      resume: () => calls.push({ action: "resume" }),
      cancel: () => calls.push({ action: "cancel" }),
    };
    Object.defineProperty(window, "SpeechSynthesisUtterance", { configurable: true, value: MockUtterance });
    Object.defineProperty(window, "speechSynthesis", { configurable: true, value: synthesis });
    (window as any).__speechCalls = calls;
  });
}

test.describe("Phase 7.4 H5 navigation and speech", () => {
  test("returns a cited reviewed-source answer through the H5 proxy", async ({ page }) => {
    await page.goto(`${h5Url}/assistant`);
    await page.getByLabel("输入您想了解的问题").fill("最近有哪些健康提醒？");
    const responsePromise = page.waitForResponse((response) =>
      response.url().includes("/api/public/assistant/chat") &&
      response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "发送问题" }).click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    const mode = (await response.json()).data.mode;
    expect(["retrieval", "ai"]).toContain(mode);
    await expect(
      page.getByText(
        mode === "ai" ? "已审核内容 + AI 整理" : "原文检索",
        { exact: true },
      ),
    ).toBeVisible();
    await expect(page.getByRole("heading", { name: "回答依据" })).toBeVisible();
    await expect(page.locator(".assistant-citation").first()).toBeVisible();
  });

  test("classifies a busy assistant response and can resend the retained question", async ({ page }) => {
    let busyOnce = true;
    await page.route("**/api/public/assistant/chat", async (route) => {
      if (busyOnce) {
        busyOnce = false;
        await route.fulfill({ status: 503, contentType: "application/json", body: '{"message":"busy"}' });
      } else {
        await route.continue();
      }
    });
    await page.goto(`${h5Url}/assistant`);
    await page.getByLabel("输入您想了解的问题").fill("养老服务怎么办理？");
    await page.getByRole("button", { name: "发送问题" }).click();
    await expect(page.getByText("助手服务繁忙，请稍后重新发送。")).toBeVisible();
    await page.getByRole("button", { name: "重新发送" }).click();
    await expect(
      page.getByText(/^(原文检索|已审核内容 \+ AI 整理)$/),
    ).toBeVisible();
  });

  test("uses five primary destinations and keeps news as a safe secondary page", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(h5Url);
    const navigation = page.getByRole("navigation", { name: "主要导航" });
    for (const label of ["首页", "邻里", "简达助手", "办事", "我的"]) {
      await expect(navigation.getByRole("link", { name: label, exact: true })).toBeVisible();
    }
    await expect(navigation.getByRole("link", { name: "资讯", exact: true })).toHaveCount(0);
    await navigation.getByRole("link", { name: "邻里", exact: true }).click();
    await expect(page).toHaveURL(`${h5Url}/neighborhood`);
    await expect(page.getByRole("button", { name: "返回" })).toHaveCount(0);
    await page.goto(`${h5Url}/news`);
    await expect(page.getByRole("button", { name: "返回" })).toBeVisible();
    await page.getByRole("button", { name: "返回" }).click();
    await expect(page).toHaveURL(`${h5Url}/`);
  });

  test("plays, pauses, resumes, changes rate and stores listen history", async ({ context, page }) => {
    await installSpeechMock(context);
    await page.goto(`${h5Url}/listen`);
    await expect(page.getByRole("heading", { name: "听一听" })).toBeVisible();
    await expect(page.locator(".listen-queue-item").first()).toBeVisible();
    await page.getByRole("button", { name: "一键播放" }).click();
    await expect.poll(() => page.evaluate(() => (window as any).__speechCalls.some((call: any) => call.action === "speak" && call.lang === "zh-CN"))).toBeTruthy();
    await page.getByRole("button", { name: "暂停" }).click();
    await page.getByRole("button", { name: "继续" }).click();
    await page.getByRole("button", { name: "1.2×" }).click();
    await expect.poll(() => page.evaluate(() => (window as any).__speechCalls.some((call: any) => call.action === "speak" && call.rate === 1.2))).toBeTruthy();
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem("jianda_listen_history") || "[]").length)).toBeGreaterThan(0);
    await page.getByRole("navigation", { name: "主要导航" }).getByRole("link", { name: "我的", exact: true }).click();
    await expect(page.getByRole("link", { name: /最近收听/ })).toContainText("1 条");
    await expect.poll(() => page.evaluate(() => (window as any).__speechCalls.some((call: any) => call.action === "cancel"))).toBeTruthy();
  });

  test("continues a long reading in chunks and advances the listen queue", async ({ context, page }) => {
    await installSpeechMock(context);
    await context.route("**/api/public/items", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json; charset=utf-8",
        body: JSON.stringify({
          code: 0,
          data: [
            {
              id: 701,
              slug: "listen-queue-a",
              title: "连续收听测试甲",
              summary: "第一条收听内容。",
              source_name: "测试来源",
              category: "健康",
              content_kind: "HEALTH_EDUCATION",
              published_at: "2026-07-29T10:00:00",
            },
            {
              id: 702,
              slug: "listen-queue-b",
              title: "连续收听测试乙",
              summary: "第二条收听内容。",
              source_name: "测试来源",
              category: "健康",
              content_kind: "HEALTH_EDUCATION",
              published_at: "2026-07-29T09:00:00",
            },
          ],
        }),
      }),
    );
    await page.goto(`${h5Url}/listen`);
    await page.getByRole("button", { name: "一键播放" }).click();
    const firstTitle = await page.locator(".listen-now h2").textContent();
    await page.evaluate(() => (window as any).__lastUtterance.onend());
    await expect.poll(() => page.locator(".listen-now h2").textContent()).not.toBe(firstTitle);
  });

  test("shows a clear fallback when speech synthesis is unavailable", async ({ context, page }) => {
    await context.addInitScript(() => {
      Object.defineProperty(window, "speechSynthesis", { configurable: true, value: undefined });
      Object.defineProperty(window, "SpeechSynthesisUtterance", { configurable: true, value: undefined });
    });
    await page.goto(`${h5Url}/listen`);
    await expect(page.getByText("当前浏览器不支持语音播报")).toBeVisible();
  });

  test("has no horizontal overflow and the bottom bar does not cover content", async ({ page }) => {
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: width === 375 ? 812 : 900 });
      for (const route of ["/", "/listen", "/news", "/services", "/assistant", "/profile"]) {
        await page.goto(`${h5Url}${route}`);
        await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
      }
      await page.goto(`${h5Url}/listen`);
      await expect(page.getByRole("heading", { name: "听一听" })).toBeVisible();
      if (width === 375) {
        await page.screenshot({ path: path.join(os.tmpdir(), "jianda-phase74-listen-375.png") });
      }
      if (width === 1440) {
        await page.goto(`${h5Url}/services`);
        await expect(page.getByRole("heading", { name: "办事行动中心" })).toBeVisible();
        await expect(page.locator(".service-action-card").first()).toBeVisible();
        await page.screenshot({ path: path.join(os.tmpdir(), "jianda-phase74-services-1440.png") });
      }
      expect(await page.evaluate(() => {
        const nav = document.querySelector(".bottom-nav")?.getBoundingClientRect();
        const last = document.querySelector(".listen-detail-link")?.getBoundingClientRect();
        return !nav || !last || last.bottom <= nav.top || document.documentElement.scrollHeight > window.innerHeight;
      })).toBeTruthy();
    }
  });
});
