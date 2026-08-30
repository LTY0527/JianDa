import { expect, test } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_TEST_URL || "http://127.0.0.1:5174";
const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

test.describe("H5 LAN HTTP compatibility", () => {
  test("returns non-empty UTF-8 JSON through the same-origin API proxy", async ({
    request,
  }) => {
    const response = await request.get(`${h5Url}/api/public/items`);
    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"].toLowerCase()).toContain(
      "application/json;charset=utf-8",
    );
    const payload = await response.json();
    expect(payload.data.length).toBeGreaterThan(0);
    expect(payload.data[0].title).toMatch(/[\u4e00-\u9fff]/);
  });

  test("renders home, news, services and assistant content through the proxy", async ({
    page,
  }) => {
    await page.goto(h5Url);
    await expect(page.getByRole("heading", { name: "推荐内容" })).toBeVisible();

    await page.goto(`${h5Url}/news`);
    await expect(page.getByRole("heading", { name: "权威资讯" })).toBeVisible();
    await expect(page.locator(".content-row").first()).toBeVisible();

    await page.goto(`${h5Url}/services`);
    await expect(page.getByRole("heading", { name: "办事行动中心" })).toBeVisible();
    await expect(page.getByText(/\d+ 个事项/)).toBeVisible();

    await page.goto(`${h5Url}/assistant`);
    await expect(page.getByRole("heading", { name: "简达助手" })).toBeVisible();
    await expect(page.locator(".chat-suggestions button").first()).toBeVisible();
  });

  test("recovers when the home retry button reloads a failed API request", async ({
    page,
  }) => {
    let failOnce = true;
    await page.route("**/api/public/items?*", async (route) => {
      if (failOnce) {
        failOnce = false;
        await route.abort("connectionfailed");
      } else {
        await route.continue();
      }
    });
    await page.goto(h5Url);
    await expect(page.getByText("内容暂时没有加载成功")).toBeVisible();
    await page.getByRole("button", { name: "重新加载" }).click();
    await expect(page.getByRole("heading", { name: "推荐内容" })).toBeVisible();
  });

  test("falls back to getRandomValues and reuses the stored visitor ID", async ({
    context,
    page,
  }) => {
    await context.addInitScript(() => {
      Object.defineProperty(Crypto.prototype, "randomUUID", {
        configurable: true,
        value: undefined,
      });
    });

    await page.goto(h5Url);
    await expect(page.getByRole("link", { name: "简达首页" })).toBeVisible();
    const firstId = await page.evaluate(() =>
      localStorage.getItem("jianda_anonymous_user"),
    );
    expect(firstId).toMatch(uuidPattern);

    await page.reload();
    await expect(page.getByRole("link", { name: "简达首页" })).toBeVisible();
    expect(
      await page.evaluate(() => localStorage.getItem("jianda_anonymous_user")),
    ).toBe(firstId);
  });

  test("mounts with the final fallback when Web Crypto methods throw", async ({
    context,
    page,
  }) => {
    await context.addInitScript(() => {
      Object.defineProperties(Crypto.prototype, {
        randomUUID: {
          configurable: true,
          value: () => {
            throw new Error("randomUUID unavailable in insecure context");
          },
        },
        getRandomValues: {
          configurable: true,
          value: () => {
            throw new Error("getRandomValues unavailable");
          },
        },
      });
    });

    await page.goto(h5Url);
    await expect(page.getByRole("link", { name: "简达首页" })).toBeVisible();
    expect(
      await page.evaluate(() => localStorage.getItem("jianda_anonymous_user")),
    ).toMatch(uuidPattern);
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  });

  test("keeps favorites, history and reader preferences in local storage", async ({
    context,
    page,
  }) => {
    await context.addInitScript(() => {
      Object.defineProperty(Crypto.prototype, "randomUUID", {
        configurable: true,
        value: undefined,
      });
    });
    await page.goto(h5Url);
    await page.evaluate(() => {
      localStorage.setItem("jianda_favorite_items", JSON.stringify([{ id: 1 }]));
      localStorage.setItem("jianda_history_items", JSON.stringify([{ id: 2 }]));
      localStorage.setItem(
        "jianda_reader_preferences",
        JSON.stringify({ autoRead: false, showRecent: true, channels: ["健康"] }),
      );
    });
    await page.reload();

    const saved = await page.evaluate(() => ({
      favorite: localStorage.getItem("jianda_favorite_items"),
      history: localStorage.getItem("jianda_history_items"),
      preferences: localStorage.getItem("jianda_reader_preferences"),
    }));
    expect(saved.favorite).toContain('"id":1');
    expect(saved.history).toContain('"id":2');
    expect(saved.preferences).toContain('"健康"');
  });
});
