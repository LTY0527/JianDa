import type { PublicItem } from "./api";

export const newsCategories = ["时政", "健康", "反诈", "文化", "养老政策", "防诈", "社区服务", "文化学习"];
export const serviceCategories = ["养老", "生活服务", "办事通知"];

export function contentKind(item: PublicItem): "news" | "guide" {
  if (item.content_kind) return item.content_kind === "SERVICE_NOTICE" ? "guide" : "news";
  if (item.slug?.startsWith("news-")) return "news";
  return newsCategories.includes(item.category) ? "news" : "guide";
}

export function stripMarkdown(value: string): string {
  return String(value || "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/(^|\s)#{1,6}\s*/g, "$1")
    .replace(/(\*\*|__)(.*?)\1/g, "$2")
    .replace(/[*_~`>|]/g, " ");
}

export function sanitizeDisplayText(value: string): string {
  let text = String(value || "").trim();
  if (
    (text.startsWith("{") && text.endsWith("}")) ||
    (text.startsWith("[") && text.endsWith("]"))
  ) {
    try {
      const parsed = JSON.parse(text);
      text = Array.isArray(parsed)
        ? parsed.join(" ")
        : typeof parsed === "object" && parsed
          ? Object.values(parsed).filter((item) => typeof item === "string").join(" ")
          : String(parsed);
    } catch {
      // Keep malformed source text and clean it below.
    }
  }
  const element = document.createElement("textarea");
  element.innerHTML = text;
  return stripMarkdown(element.value)
    .replace(/<[^>]+>/g, " ")
    .replace(/\\[nrt]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function normalizeTitle(title: string): string {
  return sanitizeDisplayText(title)
    .replace(/\s+\d{12,}$/, "")
    .replace(/\s*[-_—|｜]\s*(新华网|人民网|央视网|中国政府网|中新网|光明网)\s*$/i, "")
    .trim();
}

export function truncateSummary(summary: string, maxLength = 50): string {
  const cleaned = sanitizeDisplayText(summary);
  return cleaned.length > maxLength
    ? `${cleaned.slice(0, maxLength).replace(/[，、；：\s]+$/, "")}…`
    : cleaned;
}

export function cleanDisplayTitle(title: string): string {
  return normalizeTitle(title);
}

export function isFavorite(id: number): boolean {
  return localStorage.getItem(`favorite_${id}`) === "1";
}

export function isRead(id: number): boolean {
  return localStorage.getItem(`jianda_read_${id}`) === "1";
}

export function importanceScore(item: PublicItem): number {
  const configured = Number(item.importance || 0) + (item.pinned ? 100 : 0);
  const categoryScore = ["反诈", "防诈"].includes(item.category) ? 40 : item.category === "健康" ? 30 : ["养老", "养老政策"].includes(item.category) ? 20 : 10;
  const ageDays = Math.max(0, (Date.now() - new Date(item.published_at).getTime()) / 86_400_000);
  const freshness = Math.max(0, 30 - ageDays);
  return configured + categoryScore + freshness - (isRead(item.id) ? 12 : 0);
}
