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
  source_url?: string;
  content_kind?: string;
  cover_image_url?: string;
  cover_image_type?: "ORIGINAL_COVER" | "ARTICLE_IMAGE" | "CATEGORY_DEFAULT" | "AI_ILLUSTRATION" | "EDITOR_UPLOAD";
  image_source_name?: string;
  image_source_url?: string;
  image_alt_text?: string;
  image_cached?: boolean;
  image_license_note?: string;
  is_local?: boolean;
  reading_minutes?: number;
  pinned?: boolean;
  importance?: number;
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
  actions?: string[];
  citations: AssistantCitation[];
  disclaimer: string;
  mode: "retrieval" | "ai";
}

export type AssistantFailureReason =
  | "network"
  | "server"
  | "withdrawn"
  | "busy"
  | "format";

export class AssistantApiError extends Error {
  constructor(
    public readonly reason: AssistantFailureReason,
    public readonly status?: number,
  ) {
    super(reason);
    this.name = "AssistantApiError";
  }
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

export interface PublicItemNeighbor {
  id: number;
  slug: string;
  title: string;
  category: string;
  content_kind?: string;
  cover_image_url?: string;
}

export interface PublicItemNeighbors {
  previous: PublicItemNeighbor | null;
  next: PublicItemNeighbor | null;
}

export async function fetchNeighbors(
  slug: string,
  sameCategory = true,
): Promise<PublicItemNeighbors> {
  const response = await client.get(`/public/items/${slug}/neighbors`, {
    params: { sameCategory },
  });
  return response.data.data;
}

export async function fetchDetail(slug: string): Promise<Record<string, any>> {
  const response = await client.get(`/public/items/${slug}`);
  return response.data.data;
}

export async function recordContentView(id: number): Promise<void> {
  await client.post(`/public/items/${id}/view`);
}

export function publicOriginalFileUrl(slug: string, download = false): string {
  const base = String(import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");
  return `${base}/public/items/${encodeURIComponent(slug)}/original-file${download ? "?download=true" : ""}`;
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
  try {
    const response = await client.post("/public/assistant/chat", {
      message,
      contextSlug: contextSlug || undefined,
    });
    const data = response.data?.data;
    if (
      !data ||
      typeof data.answer !== "string" ||
      !Array.isArray(data.citations) ||
      typeof data.disclaimer !== "string" ||
      !["retrieval", "ai"].includes(data.mode)
    ) {
      throw new AssistantApiError("format");
    }
    return data as AssistantReply;
  } catch (error) {
    if (error instanceof AssistantApiError) throw error;
    if (!axios.isAxiosError(error) || !error.response) {
      throw new AssistantApiError("network");
    }
    const status = error.response.status;
    if (status === 404 || status === 410) {
      throw new AssistantApiError("withdrawn", status);
    }
    if (status === 429 || status === 503 || status === 504) {
      throw new AssistantApiError("busy", status);
    }
    throw new AssistantApiError("server", status);
  }
}
