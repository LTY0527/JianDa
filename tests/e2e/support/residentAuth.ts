import { expect, type Page } from "@playwright/test";

type ResidentSession = {
  token: string;
  profile: unknown;
};

export async function authenticateResident(
  page: Page,
  h5Url: string,
): Promise<ResidentSession> {
  const response = await page.request.post(
    `${h5Url}/api/public/resident/login`,
    {
      data: {
        username: process.env.REAL_RESIDENT_USERNAME ?? "demo_chen",
        password: process.env.REAL_RESIDENT_PASSWORD ?? "Resident@123",
      },
    },
  );
  expect(response.ok(), "居民回归账号应能通过真实登录接口建立会话").toBeTruthy();
  const payload = (await response.json()) as { data: ResidentSession };
  expect(payload.data.token).toBeTruthy();
  await page.addInitScript((session: ResidentSession) => {
    localStorage.setItem("jianda_resident_token", session.token);
    localStorage.setItem(
      "jianda_resident_profile",
      JSON.stringify(session.profile),
    );
  }, payload.data);
  return payload.data;
}
