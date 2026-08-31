import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const h5Url = process.env.JIANDA_H5_PROD_URL ?? "http://127.0.0.1";
const artifactRoot = path.resolve("artifacts/neighborhood-publish-fix-20260831");
const imagePath = path.resolve("artifacts/phase9-8-real-acceptance/resident-post-image.png");

test("REAL 邻里发布按钮与图片选择器完全解耦", async ({ page }) => {
  test.setTimeout(120_000);
  fs.mkdirSync(artifactRoot, { recursive: true });
  expect(fs.existsSync(imagePath), `缺少真实测试图片：${imagePath}`).toBeTruthy();

  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  let fileChooserCount = 0;
  page.on("filechooser", () => fileChooserCount += 1);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${h5Url}/neighborhood`, { waitUntil: "networkidle" });
  await expect(page).toHaveURL(`${h5Url}/neighborhood`);
  await expect(page).toHaveTitle(/简达/);
  await expect(page.getByRole("heading", { name: /邻里/ })).toBeVisible();

  const picker = page.getByRole("button", { name: "选择图片", exact: true });
  const publish = page.getByRole("button", { name: "发布", exact: true });
  await expect(picker).toBeVisible();
  await expect(publish).toBeDisabled();
  await page.screenshot({ path: path.join(artifactRoot, "01_neighborhood_compose_390x844.png"), fullPage: false });

  const hitboxes = await page.evaluate(() => {
    const pickerElement = document.querySelector<HTMLElement>(".image-picker")!;
    const publishElement = document.querySelector<HTMLElement>(".community-publish-button")!;
    const pickerBox = pickerElement.getBoundingClientRect();
    const publishBox = publishElement.getBoundingClientRect();
    const centerX = publishBox.left + publishBox.width / 2;
    const centerY = publishBox.top + publishBox.height / 2;
    return {
      picker: { left: pickerBox.left, right: pickerBox.right, top: pickerBox.top, bottom: pickerBox.bottom },
      publish: { left: publishBox.left, right: publishBox.right, top: publishBox.top, bottom: publishBox.bottom },
      centerTarget: document.elementFromPoint(centerX, centerY)?.closest("button")?.className ?? "",
    };
  });
  const overlapWidth = Math.max(0, Math.min(hitboxes.picker.right, hitboxes.publish.right) - Math.max(hitboxes.picker.left, hitboxes.publish.left));
  const overlapHeight = Math.max(0, Math.min(hitboxes.picker.bottom, hitboxes.publish.bottom) - Math.max(hitboxes.picker.top, hitboxes.publish.top));
  expect(overlapWidth * overlapHeight).toBe(0);
  expect(hitboxes.centerTarget).toContain("community-publish-button");
  await page.screenshot({ path: path.join(artifactRoot, "02_publish_button_independent.png"), fullPage: false });

  const runId = Date.now();
  const textContent = `验收测试：今天邻里活动正常开放 ${runId}`;
  await page.getByLabel("分享一件对邻里有用的事").fill(textContent);
  await expect(publish).toBeEnabled();
  const textCreateResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/public/community/posts"
      && response.request().method() === "POST",
  );
  await publish.click();
  expect((await textCreateResponse).ok()).toBeTruthy();
  await expect(page.getByText(textContent, { exact: true })).toBeVisible();
  expect(fileChooserCount).toBe(0);
  await page.screenshot({ path: path.join(artifactRoot, "03_text_post_published.png"), fullPage: false });

  const chooserPromise = page.waitForEvent("filechooser");
  await picker.click();
  const chooser = await chooserPromise;
  await chooser.setFiles(imagePath);
  expect(fileChooserCount).toBe(1);
  await expect(page.getByAltText("待发布图片 1")).toBeVisible();
  await page.screenshot({ path: path.join(artifactRoot, "04_image_picker_still_works.png"), fullPage: false });

  const imageContent = `验收测试：邻里图文发布正常 ${runId}`;
  await page.getByLabel("分享一件对邻里有用的事").fill(imageContent);
  const imageCreateResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/public/community/posts"
      && response.request().method() === "POST",
  );
  await publish.click();
  expect((await imageCreateResponse).ok()).toBeTruthy();
  await expect(page.getByText(imageContent, { exact: true })).toBeVisible();
  await expect(page.getByAltText(/发布的图片 1/).first()).toBeVisible();
  expect(fileChooserCount).toBe(1);
  expect(consoleErrors).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});
