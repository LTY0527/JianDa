export function cleanFilenameTitle(filename: string): string {
  return filename
    .replace(/\.[^.]+$/, "")
    .replace(/^简达[_\-\s]*/u, "")
    .replace(/^模拟材料\s*\d*[_\-\s]*/u, "")
    .replace(/[_\-—\s]+/gu, " ")
    .trim();
}
