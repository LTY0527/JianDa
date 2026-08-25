import type { PublicItem } from "./api";
import { contentKind } from "./content";

export interface LibraryItem extends PublicItem {
  kind: "news" | "guide";
  savedAt?: string;
  visitedAt?: string;
  listenedAt?: string;
}
const FAVORITES = "jianda_favorite_items";
const HISTORY = "jianda_history_items";
const LISTEN_HISTORY = "jianda_listen_history";
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
export function listenHistoryItems() { return readList(LISTEN_HISTORY).sort((a,b) => String(b.listenedAt).localeCompare(String(a.listenedAt))); }
export function saveFavorite(item: PublicItem, favorite: boolean) {
  const rest = favoriteItems().filter((entry) => entry.id !== item.id);
  writeList(FAVORITES, favorite ? [{ ...snapshot(item), savedAt: new Date().toISOString() }, ...rest] : rest);
}
export function recordVisit(item: PublicItem) {
  const rest = historyItems().filter((entry) => entry.id !== item.id);
  writeList(HISTORY, [{ ...snapshot(item), visitedAt: new Date().toISOString() }, ...rest].slice(0,50));
  localStorage.setItem(`jianda_read_${item.id}`, "1");
}
export function recordListen(item: PublicItem) {
  const rest = listenHistoryItems().filter((entry) => entry.id !== item.id);
  writeList(LISTEN_HISTORY, [{ ...snapshot(item), listenedAt: new Date().toISOString() }, ...rest].slice(0, 50));
}
export function removeHistory(id: number) { writeList(HISTORY, historyItems().filter((item) => item.id !== id)); }
export function clearHistory() { writeList(HISTORY, []); }
export function clearLocalLibrary() {
  favoriteItems().forEach((item) => localStorage.removeItem(`favorite_${item.id}`));
  historyItems().forEach((item) => localStorage.removeItem(`jianda_read_${item.id}`));
  writeList(FAVORITES, []); writeList(HISTORY, []); writeList(LISTEN_HISTORY, []);
}
export interface ReaderPreferences {
  autoRead: boolean;
  showRecent: boolean;
  channels: string[];
  desktopSideNavigation: boolean;
  mobileSwipeNavigation: boolean;
  stopSpeechOnNavigation: boolean;
  preferSameCategory: boolean;
}
const DEFAULT_PREFERENCES: ReaderPreferences = {
  autoRead: false,
  showRecent: true,
  channels: [],
  desktopSideNavigation: true,
  mobileSwipeNavigation: true,
  stopSpeechOnNavigation: true,
  preferSameCategory: true,
};
export function readerPreferences(): ReaderPreferences {
  try { return { ...DEFAULT_PREFERENCES, ...JSON.parse(localStorage.getItem(PREFERENCES) || "{}") }; }
  catch { return { ...DEFAULT_PREFERENCES }; }
}
export function saveReaderPreferences(value: ReaderPreferences) { localStorage.setItem(PREFERENCES, JSON.stringify(value)); window.dispatchEvent(new CustomEvent("jianda-preference-change")); }
