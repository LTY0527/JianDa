import type { TestInfo } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

type ArtifactTestInfo = Pick<
  TestInfo,
  "file" | "outputPath" | "project" | "retry" | "titlePath" | "workerIndex"
>;

const runId =
  process.env.JIANDA_ACCEPTANCE_RUN_ID?.trim() ||
  `${Date.now().toString(36)}-${process.pid}`;

function safePart(value: string) {
  return value
    .normalize("NFKC")
    .replace(/\.[^.]+$/, "")
    .replace(/[^\p{Letter}\p{Number}._-]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80) || "acceptance";
}

export function acceptanceArtifactPath(
  testInfo: ArtifactTestInfo,
  originalName: string,
  viewport: { width: number; height: number },
) {
  const extension = path.extname(originalName) || ".png";
  const spec = safePart(path.basename(testInfo.file));
  const title = safePart(testInfo.titlePath.join("-"));
  const requested = safePart(path.basename(originalName, path.extname(originalName)));
  const project = safePart(testInfo.project.name || "chromium");
  const filename = [
    spec,
    `${viewport.width}x${viewport.height}`,
    project,
    runId,
    `w${testInfo.workerIndex}`,
    `r${testInfo.retry}`,
    title,
    requested,
  ].join("--") + extension;
  const configured = process.env.JIANDA_ACCEPTANCE_ARTIFACT_DIR?.trim();
  if (!configured) return testInfo.outputPath(filename);
  const directory = path.resolve(configured);
  fs.mkdirSync(directory, { recursive: true });
  return path.join(directory, filename);
}
