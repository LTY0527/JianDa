import { defineConfig } from "@playwright/test";
import path from "node:path";
import os from "node:os";

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30_000,
  retries: 0,
  workers: 1,
  reporter: "line",
  outputDir: path.join(os.tmpdir(), "jianda-playwright-results"),
  use: {
    browserName: "chromium",
    trace: "retain-on-failure",
  },
});

