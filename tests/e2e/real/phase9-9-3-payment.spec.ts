import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const apiBase = process.env.JIANDA_API_URL ?? "http://127.0.0.1:8080/api";
const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/phase9-9-3-final");

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

for (const method of ["ALIPAY", "WECHAT"] as const) {
  test(`REAL ${method} 支付会话持久化、轮询并激活会员`, async ({ page, request }) => {
    test.setTimeout(45_000);
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
    const registration = await request.post(`${apiBase}/public/resident/register`, {
      data: {
        username: `pay_${method.toLowerCase()}_${suffix}`,
        password: "Phase993Test9",
        nickname: `${method}支付验收`,
        regionCode: "310113109",
      },
    });
    expect(registration.ok()).toBe(true);
    const residentToken = (await registration.json()).data.token as string;
    await page.addInitScript((token) => localStorage.setItem("jianda_resident_token", token), residentToken);

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${h5Url}/membership`, { waitUntil: "domcontentloaded" });
    await expect(page.locator(".membership-plans article").first()).toBeVisible();
    await expect(page.locator(".test-environment")).toHaveText("测试环境");
    if (method === "ALIPAY") {
      await page.screenshot({ path: path.join(artifactRoot, "h5-membership-plans-390.png"), fullPage: false });
    }
    await page.locator(".membership-plans article").first().locator("button").click();
    await page.locator(".payment-methods button").nth(method === "ALIPAY" ? 0 : 1).click();
    if (method === "ALIPAY") {
      await page.screenshot({ path: path.join(artifactRoot, "h5-payment-alipay-select-390.png"), fullPage: false });
    }

    const createdResponse = page.waitForResponse((response) =>
      response.url().endsWith("/api/public/membership/payments")
        && response.request().method() === "POST",
    );
    await page.locator(".pay-confirm").click();
    const created = await createdResponse;
    expect(created.ok()).toBe(true);
    const session = (await created.json()).data;
    expect(session.status).toBe("PENDING");
    expect(session.method).toBe(method);
    expect(String(session.qrPayload).length).toBeGreaterThan(0);
    await expect(page.locator(".qr-sheet canvas")).toBeVisible();
    await page.screenshot({
      path: path.join(artifactRoot, method === "ALIPAY"
        ? "h5-payment-alipay-qr-390.png" : "h5-payment-wechat-qr-390.png"),
      fullPage: false,
    });

    const confirmed = await request.post(`${apiBase}/internal/test/payments/${session.sessionId}/confirm`, {
      headers: { "X-Resident-Token": residentToken },
    });
    expect(confirmed.ok()).toBe(true);
    expect((await confirmed.json()).data.status).toBe("SUCCESS");

    await expect(page.locator(".membership-message.success")).toBeVisible({ timeout: 8_000 });
    await expect(page.locator(".membership-active")).toBeVisible();
    await expect(page.locator(".payment-status")).toContainText("支付成功");
    if (method === "ALIPAY") {
      await page.screenshot({ path: path.join(artifactRoot, "h5-payment-success-390.png"), fullPage: false });
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
}
