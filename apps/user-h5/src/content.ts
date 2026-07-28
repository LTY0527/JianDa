import type { PublicItem } from "./api";

export const newsCategories = ["时政", "健康", "反诈", "文化", "养老政策", "防诈", "社区服务", "文化学习"];
export const serviceCategories = ["养老", "生活服务", "办事通知"];

export function contentKind(item: PublicItem): "news" | "guide" {
  if (item.content_kind) return item.content_kind === "SERVICE_NOTICE" ? "guide" : "news";
  if (item.slug?.startsWith("news-")) return "news";
  return newsCategories.includes(item.category) ? "news" : "guide";
}

export function cleanDisplayTitle(title: string): string {
  return title.replace(/\s+\d{12,}$/, "").trim();
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
