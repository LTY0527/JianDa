import { expect, test } from "@playwright/test";

const h5Url = process.env.JIANDA_H5_TEST_URL || "http://127.0.0.1:5174";
const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

test.describe("H5 LAN HTTP compatibility", () => {
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
