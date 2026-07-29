import { expect, test } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { acceptanceArtifactPath } from "./support/acceptanceArtifacts";

function fakeInfo(root: string, workerIndex = 0, title = "截图隔离") {
  return {
    file: path.resolve("tests/e2e/acceptance.spec.ts"),
    outputPath: (name: string) => path.join(root, "playwright-output", name),
    project: { name: "chromium" },
    retry: 0,
    titlePath: ["acceptance.spec.ts", title],
    workerIndex,
  } as Parameters<typeof acceptanceArtifactPath>[0];
}

test("默认截图使用测试输出目录且不会写入历史 Phase 7.3 目录", () => {
  delete process.env.JIANDA_ACCEPTANCE_ARTIFACT_DIR;
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "jianda-artifact-default-"));
  const result = acceptanceArtifactPath(
    fakeInfo(root),
    "首页.png",
    { width: 375, height: 812 },
  );
  expect(result).toContain(path.join(root, "playwright-output"));
  expect(result).not.toContain(path.join("artifacts", "phase7-3"));
  expect(fs.existsSync(path.resolve("artifacts/phase7-3", path.basename(result)))).toBeFalsy();
});

test("显式配置后写入指定临时目录", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "jianda-artifact-explicit-"));
  process.env.JIANDA_ACCEPTANCE_ARTIFACT_DIR = directory;
  try {
    const result = acceptanceArtifactPath(
      fakeInfo(directory),
      "审核页.png",
      { width: 1440, height: 900 },
    );
    expect(path.dirname(result)).toBe(path.resolve(directory));
    expect(path.basename(result)).toContain("1440x900");
    expect(fs.existsSync(directory)).toBeTruthy();
  } finally {
    delete process.env.JIANDA_ACCEPTANCE_ARTIFACT_DIR;
  }
});

test("并发 worker、测试标题和视口生成互不冲突的文件名", () => {
  delete process.env.JIANDA_ACCEPTANCE_ARTIFACT_DIR;
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "jianda-artifact-concurrent-"));
  const paths = [
    acceptanceArtifactPath(fakeInfo(root, 0, "移动端"), "页面.png", { width: 375, height: 812 }),
    acceptanceArtifactPath(fakeInfo(root, 1, "移动端"), "页面.png", { width: 375, height: 812 }),
    acceptanceArtifactPath(fakeInfo(root, 0, "桌面端"), "页面.png", { width: 1440, height: 900 }),
  ];
  expect(new Set(paths).size).toBe(paths.length);
});
