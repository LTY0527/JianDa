import { request } from "@playwright/test";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

type ResidentSession = {
  token: string;
  profile: unknown;
};

export default async function globalResidentSetup() {
  const h5Url = process.env.JIANDA_H5_TEST_URL
    ?? process.env.JIANDA_H5_URL
    ?? "http://127.0.0.1:5174";
  const origin = new URL(h5Url).origin;
  const productionOrigin = new URL(
    process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1",
  ).origin;
  const api = await request.newContext();
  try {
    const response = await api.post(`${origin}/api/public/resident/login`, {
      data: {
        username: process.env.REAL_RESIDENT_USERNAME ?? "demo_chen",
        password: process.env.REAL_RESIDENT_PASSWORD ?? "Resident@123",
      },
    });
    if (!response.ok()) {
      throw new Error(`resident pre-authentication failed with HTTP ${response.status()}`);
    }
    const payload = await response.json() as { data?: ResidentSession };
    if (!payload.data?.token || !payload.data.profile) {
      throw new Error("resident pre-authentication returned an incomplete session");
    }
    await fs.writeFile(
      path.join(os.tmpdir(), "jianda-playwright-resident-state.json"),
      JSON.stringify({
        cookies: [],
        origins: [...new Set([origin, productionOrigin])].map((storageOrigin) => ({
          origin: storageOrigin,
          localStorage: [
            { name: "jianda_resident_token", value: payload.data.token },
            { name: "jianda_resident_profile", value: JSON.stringify(payload.data.profile) },
          ],
        })),
      }),
      "utf8",
    );
  } finally {
    await api.dispose();
  }
}
