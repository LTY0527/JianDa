import type { PublicItem } from "./api";
import { contentKind } from "./content";

export interface LibraryItem extends PublicItem {
  kind: "news" | "guide";
  savedAt?: string;
  visitedAt?: string;
}
const FAVORITES = "jianda_favorite_items";
const HISTORY = "jianda_history_items";
export const PREFERENCES = "jianda_reader_preferences";

function readList(key: string): LibraryItem[] {
  try { return JSON.parse(localStorage.getItem(key) || "[]") as LibraryItem[]; } catch { return []; }
}
function writeList(key: string, items: LibraryItem[]) {
  localStorage.setItem(key, JSON.stringify(items));
  window.dispatchEvent(new CustomEvent("jianda-library-change"));
}
function snapshot(item: PublicItem): LibraryItem { return { ...item, kind: contentKind(item) }; }
export function favoriteItems() { return readList(FAVORITES).sort((a,b) => String(b.savedAt).localeCompare(String(a.savedAt))); }
export function historyItems() { return readList(HISTORY).sort((a,b) => String(b.visitedAt).localeCompare(String(a.visitedAt))); }
export function saveFavorite(item: PublicItem, favorite: boolean) {
  const rest = favoriteItems().filter((entry) => entry.id !== item.id);
  writeList(FAVORITES, favorite ? [{ ...snapshot(item), savedAt: new Date().toISOString() }, ...rest] : rest);
}
export function recordVisit(item: PublicItem) {
  const rest = historyItems().filter((entry) => entry.id !== item.id);
  writeList(HISTORY, [{ ...snapshot(item), visitedAt: new Date().toISOString() }, ...rest].slice(0,50));
  localStorage.setItem(`jianda_read_${item.id}`, "1");
}
export function removeHistory(id: number) { writeList(HISTORY, historyItems().filter((item) => item.id !== id)); }
export function clearHistory() { writeList(HISTORY, []); }
export function clearLocalLibrary() {
  favoriteItems().forEach((item) => localStorage.removeItem(`favorite_${item.id}`));
  historyItems().forEach((item) => localStorage.removeItem(`jianda_read_${item.id}`));
  writeList(FAVORITES, []); writeList(HISTORY, []);
}
export interface ReaderPreferences { autoRead: boolean; showRecent: boolean; channels: string[]; }
export function readerPreferences(): ReaderPreferences {
  try { return { autoRead: false, showRecent: true, channels: [], ...JSON.parse(localStorage.getItem(PREFERENCES) || "{}") }; }
  catch { return { autoRead: false, showRecent: true, channels: [] }; }
}
export function saveReaderPreferences(value: ReaderPreferences) { localStorage.setItem(PREFERENCES, JSON.stringify(value)); window.dispatchEvent(new CustomEvent("jianda-preference-change")); }