import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const institutionUrl =
  process.env.JIANDA_INSTITUTION_PROD_URL ?? "http://127.0.0.1:8090";
const dockerComposeUp = process.env.JIANDA_DOCKER_COMPOSE_UP === "1";

function expectBuiltApp(appPath: string) {
  const dist = path.resolve(appPath, "dist");
  const assets = path.join(dist, "assets");

  expect(fs.existsSync(path.join(dist, "index.html"))).toBe(true);
  expect(fs.existsSync(assets)).toBe(true);

  const files = fs.readdirSync(assets);
  expect(files.some((file) => file.endsWith(".js"))).toBe(true);
  expect(files.some((file) => file.endsWith(".css"))).toBe(true);
  expect(
    files.some((file) => /-[0-9A-Za-z_-]{8,}\.(?:js|css)$/.test(file)),
  ).toBe(true);
}

test.describe("Phase 8.2 build integrity", () => {
  test("both Vue applications produce index and hashed assets", () => {
    expectBuiltApp("apps/user-h5");
    expectBuiltApp("apps/institution-web");
  });

  test("institution web uses same-origin API and a development proxy", () => {
    const api = fs.readFileSync(
      "apps/institution-web/src/api/http.ts",
      "utf-8",
    );
    const vite = fs.readFileSync(
      "apps/institution-web/vite.config.ts",
      "utf-8",
    );

    expect(api).not.toContain("127.0.0.1:8080/api");
    expect(api).toContain('|| "/api"');
    expect(vite).toContain('"/api"');
    expect(vite).toContain("proxy");
  });

  test("frontend image builds both applications and configures Nginx", () => {
    const dockerfile = fs.readFileSync("Dockerfile.frontend", "utf-8");
    const nginx = fs.readFileSync("nginx/frontend.conf", "utf-8");
    const compose = fs.readFileSync("docker-compose.yml", "utf-8");
    const dockerignore = fs.readFileSync(".dockerignore", "utf-8");

    expect(dockerfile).toContain("apps/institution-web");
    expect(dockerfile).toContain("apps/user-h5");
    expect(dockerfile).toContain("nginx");
    expect(nginx).toContain("try_files $uri $uri/ /index.html");
    expect(nginx).toContain("location /api/");
    expect(nginx).toContain("client_max_body_size 25m");
    expect(nginx).toContain("immutable");
    expect(nginx).toContain("X-Content-Type-Options");
    expect(nginx).toContain("listen 8090");
    expect(compose).toContain("Dockerfile.frontend");
    expect(compose).toContain("INSTITUTION_PORT");
    expect(dockerignore).toContain("node_modules");
    expect(dockerignore).toContain("**/dist");
  });
});

test.describe("Phase 8.2 Nginx smoke", () => {
  test.skip(
    !dockerComposeUp,
    "Run docker compose up --build -d and set JIANDA_DOCKER_COMPOSE_UP=1",
  );

  test("health and SPA fallback work on both frontends", async ({ request }) => {
    for (const [baseUrl, route] of [
      [h5Url, "/assistant"],
      [institutionUrl, "/documents"],
    ] as const) {
      const health = await request.get(`${baseUrl}/health`);
      expect(health.status()).toBe(200);
      expect(await health.text()).toContain("ok");

      const deepRoute = await request.get(`${baseUrl}${route}`);
      expect(deepRoute.status()).toBe(200);
      expect(await deepRoute.text()).toContain('<div id="app"></div>');
    }
  });

  test("same-origin API proxy serves public content", async ({ request }) => {
    for (const baseUrl of [h5Url, institutionUrl]) {
      const response = await request.get(`${baseUrl}/api/public/items`);
      expect(response.status()).toBe(200);
      expect(response.headers()["content-type"]).toContain("application/json");
    }
  });

  test("H5 renders at mobile width without console or page errors", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];

    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(h5Url);
    await expect(page.locator("#app")).not.toBeEmpty();
    expect(consoleErrors).toEqual([]);
    expect(pageErrors).toEqual([]);
  });

  test("institution login renders at desktop width", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${institutionUrl}/login`);
    await expect(page.locator("#app")).not.toBeEmpty();
    await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  });

  test("HTML and hashed assets expose deployment headers", async ({ request }) => {
    const html = await request.get(h5Url);
    expect(html.headers()["x-frame-options"]).toBe("SAMEORIGIN");
    expect(html.headers()["x-content-type-options"]).toBe("nosniff");

    const body = await html.text();
    const assetPath = body.match(/\/assets\/[^"']+\.(?:js|css)/)?.[0];
    expect(assetPath).toBeTruthy();

    const asset = await request.get(`${h5Url}${assetPath}`);
    expect(asset.headers()["cache-control"]).toContain("immutable");
  });
});
