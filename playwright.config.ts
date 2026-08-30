import { defineConfig } from "@playwright/test";
import path from "node:path";
import os from "node:os";

const residentStatePath = path.join(os.tmpdir(), "jianda-playwright-resident-state.json");

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30_000,
  retries: 0,
  workers: 1,
  reporter: "line",
  globalSetup: "./tests/e2e/support/globalResidentSetup.ts",
  outputDir: path.join(os.tmpdir(), "jianda-playwright-results"),
  use: {
    browserName: "chromium",
    trace: "retain-on-failure",
    storageState: residentStatePath,
  },
});
