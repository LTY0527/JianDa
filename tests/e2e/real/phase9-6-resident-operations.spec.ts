import { expect, test } from "@playwright/test";

const backendUrl = process.env.JIANDA_BACKEND_PROD_URL ?? "http://127.0.0.1:8080";
const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const residentPassword = process.env.JIANDA_REAL_RESIDENT_PASSWORD;
const platformPassword = process.env.JIANDA_REAL_PLATFORM_PASSWORD;

test.describe("Phase 9.6 真实居民、邻里与运营闭环", () => {
  test.skip(
    process.env.JIANDA_DOCKER_COMPOSE_UP !== "1" || !residentPassword || !platformPassword,
    "仅在显式提供本地 DEMO 验收凭据并启动真实 Docker/MySQL 时运行",
  );

  test("真实登录、互动、治理、提醒和运营指标形成闭环", async ({ page, request }, testInfo) => {
    const api = `${backendUrl}/api`;
    const runId = `P96-${Date.now()}`;
    const postContent = `[${runId}] 大场镇邻里真实验收帖：交流社区便民服务信息。`;

    const residentLogin = await request.post(`${api}/public/resident/login`, {
      data: { username: "demo_chen", password: residentPassword },
    });
    expect(residentLogin.ok()).toBeTruthy();
    const residentData = (await residentLogin.json()).data;
    expect(residentData.profile.demo).toBe(true);
    const residentHeaders = { "X-Resident-Token": String(residentData.token) };

    const me = await request.get(`${api}/public/resident/me`, { headers: residentHeaders });
    expect(me.ok()).toBeTruthy();
    expect((await me.json()).data.regionCode).toBe("310113102");

    const platformLogin = await request.post(`${api}/auth/login`, {
      data: { username: "platform_admin", password: platformPassword },
    });
    expect(platformLogin.ok()).toBeTruthy();
    const platformToken = String((await platformLogin.json()).data.token);
    const platformHeaders = { Authorization: `Bearer ${platformToken}` };

    const beforeResponse = await request.get(`${api}/operation-metrics`, { headers: platformHeaders });
    expect(beforeResponse.ok()).toBeTruthy();
    const before = (await beforeResponse.json()).data;

    const created = await request.post(`${api}/public/community/posts`, {
      headers: residentHeaders,
      data: { category: "互助", content: postContent },
    });
    expect(created.ok()).toBeTruthy();
    const postId = Number((await created.json()).data.id);
    expect(postId).toBeGreaterThan(0);

    const listed = await request.get(`${api}/public/community/posts?regionCode=310113102`);
    expect((await listed.json()).data.some((item: { id: number }) => item.id === postId)).toBe(true);

    const liked = await request.post(`${api}/public/community/posts/${postId}/like`, {
      headers: residentHeaders,
    });
    expect((await liked.json()).data.liked).toBe(true);
    expect((await request.post(`${api}/public/community/posts/${postId}/comments`, {
      headers: residentHeaders,
      data: { content: `[${runId}] 已核对这条便民信息。` },
    })).ok()).toBeTruthy();
    expect((await request.post(`${api}/public/community/posts/${postId}/report`, {
      headers: residentHeaders,
      data: { reason: `[${runId}] 验收举报流程，不涉及真实居民。` },
    })).ok()).toBeTruthy();

    const moderation = await request.get(`${api}/community-admin/posts`, { headers: platformHeaders });
    expect((await moderation.json()).data.some((item: { id: number; status: string }) =>
      item.id === postId && item.status === "REPORTED")).toBe(true);

    expect((await request.post(`${api}/community-admin/posts/${postId}/status`, {
      headers: platformHeaders,
      data: { status: "HIDDEN" },
    })).ok()).toBeTruthy();
    const hiddenList = await request.get(`${api}/public/community/posts?regionCode=310113102`);
    expect((await hiddenList.json()).data.some((item: { id: number }) => item.id === postId)).toBe(false);

    expect((await request.post(`${api}/community-admin/posts/${postId}/status`, {
      headers: platformHeaders,
      data: { status: "VISIBLE" },
    })).ok()).toBeTruthy();

    const detail = await request.get(`${api}/public/items/news-63`);
    expect(detail.ok()).toBeTruthy();
    const itemId = Number((await detail.json()).data.id);
    const anonymousHeaders = { "X-Anonymous-User": `p96-${crypto.randomUUID()}` };
    expect((await request.post(`${api}/public/items/${itemId}/view`)).ok()).toBeTruthy();
    expect((await request.post(`${api}/public/items/${itemId}/reminder`, {
      headers: anonymousHeaders,
      data: { reminderType: "CONTENT_TIME", remindAt: new Date(Date.now() + 14 * 86400_000).toISOString() },
    })).ok()).toBeTruthy();
    for (const event of ["CONTENT_LISTEN", "SERVICE_PHONE_CLICK", "SERVICE_ADDRESS_COPY"]) {
      expect((await request.post(`${api}/public/items/${itemId}/event/${event}`, {
        headers: anonymousHeaders,
      })).ok()).toBeTruthy();
    }

    const after = (await (await request.get(`${api}/operation-metrics`, {
      headers: platformHeaders,
    })).json()).data;
    expect(after.weeklyViewCount).toBeGreaterThanOrEqual(before.weeklyViewCount + 1);
    expect(after.weeklyListenCount).toBeGreaterThanOrEqual(before.weeklyListenCount + 1);
    expect(after.weeklyReminderCount).toBeGreaterThanOrEqual(before.weeklyReminderCount + 1);

    await page.addInitScript(({ token }) => {
      localStorage.setItem("jianda_resident_token", token);
      localStorage.setItem("jianda_region_code", "310113102");
    }, { token: String(residentData.token) });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/neighborhood`);
    await expect(page.getByText(postContent)).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await page.screenshot({ path: testInfo.outputPath(`real-neighborhood-${postId}.png`), fullPage: false });
  });
});
