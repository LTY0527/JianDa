import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_URL ?? "http://127.0.0.1";
const institutionUrl = process.env.JIANDA_INSTITUTION_URL ?? "http://127.0.0.1:8090";
const apiBase = process.env.JIANDA_API_URL ?? "http://127.0.0.1:8080/api";
const artifactRoot = path.resolve("artifacts/phase9-9-3-final");
const adminAccount = process.env.JIANDA_ADMIN_ACCOUNT ?? "platform_admin";
const adminPassword = process.env.JIANDA_ADMIN_PASSWORD ?? "Jianda@123";

test.beforeAll(() => fs.mkdirSync(artifactRoot, { recursive: true }));

const N = 30;

function percentile(sorted: number[], p: number): number {
  if (!sorted.length) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

async function timeFetch(page: import("@playwright/test").Page, url: string, headers: Record<string, string> = {}): Promise<{ status: number; ms: number; bytes: number }> {
  const result = await page.evaluate(async ({ u, h }) => {
    const t0 = performance.now();
    const res = await fetch(u, { credentials: "include", headers: h });
    const text = await res.text();
    const t1 = performance.now();
    return { status: res.status, ms: Math.round(t1 - t0), bytes: text.length };
  }, { u: url, h: headers });
  return result;
}

test("PERF H5 首页公开内容列表 p95 < 1.5s", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "networkidle" });
  const visitorId = `perf-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  const headers = { "X-Anonymous-User": visitorId, "X-Visitor-Id": visitorId };

  const samples: number[] = [];
  let lastStatus = 0;
  let lastBytes = 0;
  for (let i = 0; i < N; i++) {
    const r = await timeFetch(page, `/api/public/items?regionCode=310113102`, headers);
    lastStatus = r.status;
    lastBytes = r.bytes;
    samples.push(r.ms);
    expect(r.status, `sample ${i} status`).toBe(200);
  }
  samples.sort((a, b) => a - b);
  const p50 = percentile(samples, 50);
  const p95 = percentile(samples, 95);
  const p99 = percentile(samples, 99);
  const max = samples[samples.length - 1];

  const summary = `H5 /public/items N=${N} p50=${p50}ms p95=${p95}ms p99=${p99}ms max=${max}ms bytes=${lastBytes} status=${lastStatus}`;
  fs.writeFileSync(path.join(artifactRoot, "perf-h5-items.txt"), summary + "\n" + JSON.stringify(samples) + "\n");
  console.log(summary);
  expect(p95, `p95 ${p95}ms should be < 1500ms`).toBeLessThan(1500);
  expect(lastStatus).toBe(200);
  expect(lastBytes, "should return real content not empty").toBeGreaterThan(100);
});

test("PERF H5 服务目录 p95 < 1.5s", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(h5Url, { waitUntil: "networkidle" });
  const visitorId = `perf-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  const headers = { "X-Anonymous-User": visitorId, "X-Visitor-Id": visitorId };

  const samples: number[] = [];
  let lastStatus = 0;
  for (let i = 0; i < N; i++) {
    const r = await timeFetch(page, `/api/public/service-directory?regionCode=310113`, headers);
    lastStatus = r.status;
    samples.push(r.ms);
    expect(r.status).toBe(200);
  }
  samples.sort((a, b) => a - b);
  const p95 = percentile(samples, 95);
  const summary = `H5 /public/service-directory N=${N} p95=${p95}ms status=${lastStatus}`;
  fs.writeFileSync(path.join(artifactRoot, "perf-h5-service-directory.txt"), summary + "\n" + JSON.stringify(samples) + "\n");
  console.log(summary);
  expect(p95).toBeLessThan(1500);
});

test("PERF 管理端文档列表 p95 < 1.5s", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${institutionUrl}/login`, { waitUntil: "domcontentloaded" });
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(adminAccount);
  await page.getByRole("textbox", { name: "密码", exact: true }).fill(adminPassword);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).not.toHaveURL(/\/login$/, { timeout: 15_000 });

  const token = await page.evaluate(() => localStorage.getItem("jianda_token") || "");

  const samples: number[] = [];
  let lastStatus = 0;
  for (let i = 0; i < N; i++) {
    const r = await page.evaluate(async (args) => {
      const t0 = performance.now();
      const res = await fetch(args.url, { headers: { Authorization: `Bearer ${args.token}` } });
      const text = await res.text();
      const t1 = performance.now();
      return { status: res.status, ms: Math.round(t1 - t0), bytes: text.length };
    }, { url: `${institutionUrl}/api/documents`, token });
    lastStatus = r.status;
    samples.push(r.ms);
    expect(r.status, `sample ${i} status ${r.status}`).toBe(200);
  }
  samples.sort((a, b) => a - b);
  const p95 = percentile(samples, 95);
  const summary = `Admin /documents N=${N} p95=${p95}ms status=${lastStatus}`;
  fs.writeFileSync(path.join(artifactRoot, "perf-admin-documents.txt"), summary + "\n" + JSON.stringify(samples) + "\n");
  console.log(summary);
  expect(p95).toBeLessThan(1500);
});

test("PERF 创建支付会话可正常返回（不超时）", async ({ request, page }) => {
  test.setTimeout(60_000);
  const plansResp = await request.get(`${apiBase}/public/membership/plans`);
  expect(plansResp.ok()).toBe(true);
  const plans = (await plansResp.json()).data as Array<{ id: number; plan_code: string }>;
  const annual = plans.find((p) => p.plan_code === "ANNUAL") ?? plans[0];
  expect(annual, "should have at least one membership plan").toBeDefined();

  const suffix = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
  const registration = await request.post(`${apiBase}/public/resident/register`, {
    data: {
      username: `perf_pay_${suffix}`,
      password: "Phase993Test9",
      nickname: "性能验收支付",
      regionCode: "310113109",
    },
  });
  expect(registration.ok()).toBe(true);
  const residentToken = (await registration.json()).data.token as string;

  const samples: number[] = [];
  let lastStatus = 0;
  let lastSessionId = "";
  for (let i = 0; i < 5; i++) {
    const t0 = Date.now();
    const r = await request.post(`${apiBase}/public/membership/payments`, {
      headers: { "X-Resident-Token": residentToken, "Content-Type": "application/json" },
      data: { planId: annual.id, method: "ALIPAY" },
      timeout: 5000,
    });
    const elapsed = Date.now() - t0;
    samples.push(elapsed);
    lastStatus = r.status();
    const json = await r.json().catch(() => ({}));
    lastSessionId = json?.data?.sessionId ?? lastSessionId;
  }
  samples.sort((a, b) => a - b);
  const p95 = percentile(samples, 95);
  const summary = `POST /payments (planId=${annual.id}, method=ALIPAY, 5x) p95=${p95}ms status=${lastStatus} sessionId=${lastSessionId}`;
  fs.writeFileSync(path.join(artifactRoot, "perf-payment-session.txt"), summary + "\n" + JSON.stringify(samples) + "\n");
  console.log(summary);
  expect([200, 201]).toContain(lastStatus);
  expect(p95).toBeLessThan(1500);
  expect(lastSessionId.length).toBeGreaterThan(0);
});
