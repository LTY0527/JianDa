import axios from "axios";
import { getOrCreateAnonymousUserId } from "./utils/visitorId";

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 10000,
});
const anonymousUser = getOrCreateAnonymousUserId();
client.defaults.headers.common["X-Anonymous-User"] = anonymousUser;

export interface PublicItem {
  id: number;
  slug: string;
  title: string;
  summary: string;
  category: string;
  source_name: string;
  published_at: string;
}

export interface AssistantCitation {
  title: string;
  slug: string;
  kind: "news" | "guide";
  category: string;
  sourceName: string;
  publishedAt: string;
  quote: string;
}

export interface AssistantReply {
  answer: string;
  citations: AssistantCitation[];
  disclaimer: string;
}

export async function fetchItems(category?: string): Promise<PublicItem[]> {
  const response = await client.get("/public/items", {
    params: category && category !== "全部" ? { category } : {},
  });
  return response.data.data;
}

export async function searchItems(keyword: string): Promise<PublicItem[]> {
  const response = await client.get("/public/search", { params: { keyword } });
  return response.data.data;
}

export async function fetchDetail(slug: string): Promise<Record<string, any>> {
  const response = await client.get(`/public/items/${slug}`);
  return response.data.data;
}

export async function setFavorite(
  id: number,
  favorite: boolean,
): Promise<void> {
  await client.request({
    url: `/public/items/${id}/favorite`,
    method: favorite ? "POST" : "DELETE",
  });
}

export async function fetchAssistantSuggestions(): Promise<string[]> {
  const response = await client.get("/public/assistant/suggestions");
  return response.data.data;
}

export async function askAssistant(
  message: string,
  contextSlug?: string,
): Promise<AssistantReply> {
  const response = await client.post("/public/assistant/chat", {
    message,
    contextSlug: contextSlug || undefined,
  });
  return response.data.data;
}
