import { expect, test } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1:5174";

test.beforeEach(async ({ context }) => {
  await context.addInitScript(() => {
    const calls: Array<{ action: string; text?: string; voice?: string }> = [];
    const listeners: Record<string, Array<() => void>> = {};
    class MockUtterance {
      text: string;
      lang = "";
      rate = 1;
      voice: { name: string; lang: string } | null = null;
      onend: null | (() => void) = null;
      onerror: null | ((event: { error: string }) => void) = null;
      constructor(text: string) { this.text = text; }
    }
    const synthesis = {
      getVoices: () => [
        { name: "English", lang: "en-US" },
        { name: "普通话", lang: "zh-Hans-CN" },
      ],
      addEventListener: (name: string, listener: () => void) => {
        (listeners[name] ||= []).push(listener);
      },
      removeEventListener: () => undefined,
      speak: (utterance: MockUtterance) => {
        calls.push({ action: "speak", text: utterance.text, voice: utterance.voice?.lang });
        (window as any).__lastUtterance = utterance;
      },
      cancel: () => calls.push({ action: "cancel" }),
    };
    Object.defineProperty(window, "SpeechSynthesisUtterance", { configurable: true, value: MockUtterance });
    Object.defineProperty(window, "speechSynthesis", { configurable: true, value: synthesis });
    (window as any).__speechCalls = calls;
    (window as any).__emitVoicesChanged = () => listeners.voiceschanged?.forEach((listener) => listener());
  });
  const longText = "请先核对办理条件。".repeat(30);
  await context.route("**/api/public/items/ios-speech/neighbors**", (route) =>
    route.fulfill({ contentType: "application/json", body: '{"code":0,"data":{"previous":null,"next":null}}' }),
  );
  await context.route("**/api/public/items/901/view", (route) =>
    route.fulfill({ contentType: "application/json", body: '{"code":0,"data":null}' }),
  );
  await context.route("**/api/public/items/ios-speech", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        code: 0,
        data: {
          id: 901,
          slug: "ios-speech",
          title: "iPhone朗读兼容测试",
          category: "生活服务",
          source_name: "测试权威来源",
          published_at: "2026-07-29T10:00:00",
          fields: [],
          generated: { SUMMARY: [longText], STEP_CARDS: [], RISK_WARNING: [] },
        },
      }),
    }),
  );
});

test("iPhone Safari 分段朗读只由点击触发，暂停继续会重建当前段", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/guide/ios-speech`);
  await expect(page.getByRole("heading", { name: "iPhone朗读兼容测试" })).toBeVisible();
  expect(await page.evaluate(() => (window as any).__speechCalls.filter((call: any) => call.action === "speak").length)).toBe(0);

  await page.evaluate(() => (window as any).__emitVoicesChanged());
  await page.getByRole("button", { name: "听全文" }).first().click();
  await expect(page.getByRole("status").filter({ hasText: /第 1 \// }).first()).toBeVisible();
  const first = await page.evaluate(() => (window as any).__lastUtterance);
  expect(first.voice.lang).toBe("zh-Hans-CN");
  expect(first.text.length).toBeLessThanOrEqual(120);

  await page.getByRole("button", { name: "暂停" }).first().click();
  await page.getByRole("button", { name: "继续" }).first().click();
  const resumed = await page.evaluate(() => (window as any).__lastUtterance);
  expect(resumed).not.toBe(first);
  expect(resumed.text).toBe(first.text);
  expect(await page.evaluate(() => (window as any).__speechCalls.filter((call: any) => call.action === "cancel").length)).toBeGreaterThan(0);

  await page.getByRole("button", { name: "暂停" }).first().click();
  await page.getByRole("button", { name: "继续" }).first().click();
  const speakCalls = await page.evaluate(() => (window as any).__speechCalls.filter((call: any) => call.action === "speak").length);
  expect(speakCalls).toBe(3);
  await page.evaluate(() => {
    history.pushState({}, "", "/");
    window.dispatchEvent(new PopStateEvent("popstate"));
  });
  await expect.poll(() => page.evaluate(() => (window as any).__speechCalls.at(-1)?.action)).toBe("cancel");
});
