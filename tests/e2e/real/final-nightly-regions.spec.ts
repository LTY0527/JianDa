import { expect, test } from "@playwright/test";
import { authenticateResident } from "../support/residentAuth";

const h5Url = process.env.JIANDA_H5_TEST_URL ?? "http://127.0.0.1";
const regions = [
  { code: "310113102", name: "大场镇" },
  { code: "310113109", name: "顾村镇" },
  { code: "310113112", name: "庙行镇" },
] as const;

test("REAL 三区域公开内容严格隔离且社区演示内容有明确标记", async ({ request }) => {
  const regionResponse = await request.get(`${h5Url}/api/public/regions`);
  expect(regionResponse.ok()).toBeTruthy();
  const regionPayload = await regionResponse.json();
  expect(regionPayload.data.map((item: any) => item.region_code).sort()).toEqual(
    regions.map((item) => item.code).sort(),
  );

  for (const region of [regions[1], regions[2], regions[0]]) {
    const itemResponse = await request.get(
      `${h5Url}/api/public/items?regionCode=${region.code}`,
    );
    expect(itemResponse.ok()).toBeTruthy();
    const items = (await itemResponse.json()).data as any[];
    const townItems = items.filter((item) =>
      ["LOCAL_TOWN", "TOWN", "STREET", "LOCAL"].includes(item.local_scope),
    );
    if (region.code === "310113109" && townItems.length === 0) {
      const archivedResponse = await request.get(`${h5Url}/api/public/items/news-122`);
      expect(archivedResponse.ok(), "顾村历史项目应保留可追溯详情").toBeTruthy();
      const archived = (await archivedResponse.json()).data;
      expect(archived.region_code).toBe(region.code);
      expect(archived.local_scope).toBe("LOCAL_TOWN");
    } else {
      expect(townItems.length, `${region.name}应有当前镇级内容`).toBeGreaterThan(0);
    }
    expect(
      townItems.every((item) => item.region_code === region.code),
      `${region.name}不得混入其他镇的镇级内容`,
    ).toBeTruthy();

    const communityResponse = await request.get(
      `${h5Url}/api/public/community/posts?regionCode=${region.code}`,
    );
    expect(communityResponse.ok()).toBeTruthy();
    const posts = (await communityResponse.json()).data as any[];
    expect(posts.filter((post) => post.user_is_demo).length).toBeGreaterThanOrEqual(6);
    expect(posts.every((post) => post.region_code === region.code)).toBeTruthy();
  }
});

test("REAL 390px 区域切换、类别封面和演示标签均正常渲染", async ({ page }) => {
  test.setTimeout(90_000);
  const consoleErrors: string[] = [];
  page.on("pageerror", (error) => consoleErrors.push(error.message));
  await authenticateResident(page, h5Url);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "networkidle" });

  for (const region of [regions[1], regions[2], regions[0]]) {
    const regionButton = page.getByRole("button", { name: "选择所在地区" });
    await regionButton.click();
    await Promise.all([
      page.waitForResponse((response) =>
        response.url().includes(`/api/public/items?regionCode=${region.code}`)
        && response.status() === 200,
      ),
      page.getByRole("button", { name: new RegExp(region.name) }).last().click(),
    ]);
    await expect(regionButton).toContainText(region.name);
    await expect(page.locator("main")).not.toBeEmpty();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    const images = page.locator("main img:visible");
    for (let index = 0; index < await images.count(); index += 1) {
      await images.nth(index).scrollIntoViewIfNeeded();
    }
    await page.waitForTimeout(300);
    const brokenImages = await page.locator("main img:visible").evaluateAll((images) =>
      images.filter((image) => {
        const element = image as HTMLImageElement;
        return !element.complete || element.naturalWidth <= 0 || element.naturalHeight <= 0;
      }).length,
    );
    expect(brokenImages, `${region.name}首页不得出现损坏图片`).toBe(0);
  }

  await page.goto(`${h5Url}/neighborhood`, { waitUntil: "networkidle" });
  await expect(page.getByText("演示社区内容").first()).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  expect(consoleErrors).toEqual([]);
});
