function warningKey(value: string): string {
  return value
    .trim()
    .replace(/[，,。；;！!\s]/g, "")
    .replace(/无需/g, "不需要")
    .replace(/向您/g, "")
    .replace(/您/g, "");
}

export function sameDisplayText(left: string, right: string): boolean {
  return warningKey(left) === warningKey(right);
}

export function deduplicateWarnings(values: string[]): string[] {
  const result: string[] = [];
  const keys: string[] = [];
  for (const value of values) {
    for (const rawClause of String(value || "").split(/[。；;]+/)) {
      const clause = rawClause
        .trim()
        .replace(/^[，,]+|[，,]+$/g, "")
        .replace(/无需/g, "不需要");
      if (!clause) continue;
      const key = warningKey(clause);
      if (keys.includes(key)) {
        const index = keys.indexOf(key);
        if (clause.includes("不需要") && result[index].includes("无需")) {
          result[index] = `${clause}。`;
        }
        continue;
      }
      result.push(`${clause}。`);
      keys.push(key);
    }
  }
  return result;
}
