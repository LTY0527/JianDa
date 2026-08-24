import axios from "axios";
import { getOrCreateAnonymousUserId } from "./utils/visitorId";

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 10000,
});
const anonymousUser = getOrCreateAnonymousUserId();
client.defaults.headers.common["X-Anonymous-User"] = anonymousUser;
client.defaults.headers.common["X-Visitor-Id"] = anonymousUser;

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
  cover_image_type?: "ORIGINAL_COVER" | "ARTICLE_IMAGE" | "CATEGORY_DEFAULT" | "AI_ILLUSTRATION" | "EDITOR_UPLOAD" | "UPLOADED_ORIGINAL" | "PDF_FIRST_PAGE";
  image_source_name?: string;
  image_source_url?: string;
  image_alt_text?: string;
  image_cached?: boolean;
  image_license_note?: string;
  is_local?: boolean;
  reading_minutes?: number;
  pinned?: boolean;
  importance?: number;
  province?: string;
  city?: string;
  district?: string;
  street_or_town?: string;
  community?: string;
  region_code?: string;
  local_scope?: string;
  effective_from?: string;
  deadline_at?: string;
  expires_at?: string;
  last_verified_at?: string;
  source_updated_at?: string;
  verification_status?: "VERIFIED" | "REVIEW_REQUIRED";
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
  factCards?: AssistantFactCard[];
  communityPosts?: AssistantCommunityPost[];
  citations: AssistantCitation[];
  disclaimer: string;
  mode: "status" | "retrieval" | "ai" | "general_ai" | "community_post";
  assistantStatus?: AssistantRuntimeStatus;
}

export type AssistantRuntimeStatus = "ready" | "degraded" | "unreachable" | "disabled";

export interface AssistantStatus {
  status: AssistantRuntimeStatus;
  retrieval: "ready";
  external: AssistantRuntimeStatus;
}

export interface AssistantCommunityPost {
  id: number;
  category: string;
  content: string;
  nickname: string;
  region_code: string;
  district: string;
  street_or_town: string;
  created_at: string;
}

export interface AssistantFactCard {
  type: "deadline" | "location" | "phone" | "fee" | "material" | "fact";
  label: string;
  value: string;
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

export async function fetchItems(category?: string, regionCode?: string): Promise<PublicItem[]> {
  const response = await client.get("/public/items", {
    params: {
      ...(category && category !== "全部" ? { category } : {}),
      ...(regionCode ? { regionCode } : {}),
    },
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

export interface ServiceDirectoryItem {
  id: number;
  name: string;
  service_type: string;
  district?: string;
  street_or_town?: string;
  community?: string;
  address?: string;
  phone?: string;
  opening_hours?: string;
  description: string;
  source_url: string;
  source_name: string;
  last_verified_at?: string;
}

export interface ResidentReminder {
  id: number;
  reminder_type: "CONTENT_TIME" | "DEADLINE" | "ACTIVITY_START";
  remind_at: string;
  published_item_id: number;
  slug: string;
  title: string;
  category: string;
  content_kind?: string;
  content_status: string;
}

export async function fetchServiceDirectory(regionCode: string): Promise<ServiceDirectoryItem[]> {
  const response = await client.get("/public/service-directory", { params: { regionCode } });
  return response.data.data;
}

export async function createReminder(
  id: number,
  reminderType: ResidentReminder["reminder_type"],
  remindAt: string,
): Promise<void> {
  await client.post(`/public/items/${id}/reminder`, { reminderType, remindAt });
}

export async function fetchReminders(): Promise<ResidentReminder[]> {
  const response = await client.get("/public/reminders");
  return response.data.data;
}

export async function deleteReminder(id: number): Promise<void> {
  await client.delete(`/public/reminders/${id}`);
}

export async function recordUsageEvent(
  id: number,
  eventType: "CONTENT_LISTEN" | "SERVICE_PHONE_CLICK" | "SERVICE_ADDRESS_COPY",
): Promise<void> {
  await client.post(`/public/items/${id}/event/${eventType}`);
}

export interface ResidentProfile {
  id: number; username: string; nickname: string; district: string;
  streetOrTown: string; regionCode: string; demo: boolean;
}
export interface CommunityMedia {
  id: number; mimeType: string; width: number; height: number; url: string; thumbnailUrl: string;
}
export interface CommunityPost {
  id: number; category: "最新" | "互助" | "活动"; content: string; region_code: string;
  district: string; street_or_town: string; status: "VISIBLE" | "REPORTED";
  is_demo: boolean; created_at: string; nickname: string; user_is_demo: boolean;
  like_count: number; comment_count: number; media: CommunityMedia[];
}
const residentHeaders = () => ({ "X-Resident-Token": localStorage.getItem("jianda_resident_token") || "" });
export async function residentLogin(username: string, password: string): Promise<ResidentProfile> {
  const response = await client.post("/public/resident/login", { username, password });
  localStorage.setItem("jianda_resident_token", response.data.data.token);
  localStorage.setItem("jianda_resident_profile", JSON.stringify(response.data.data.profile));
  return response.data.data.profile;
}
export async function residentRegister(username: string, password: string, nickname: string): Promise<ResidentProfile> {
  const response = await client.post("/public/resident/register", { username, password, nickname });
  localStorage.setItem("jianda_resident_token", response.data.data.token);
  localStorage.setItem("jianda_resident_profile", JSON.stringify(response.data.data.profile));
  return response.data.data.profile;
}
export async function residentRegistrationCapabilities(): Promise<{ usernamePassword: boolean; sms: { enabled: boolean; message: string } }> {
  const response = await client.get("/public/resident/registration-capabilities");
  return response.data.data;
}
export async function residentMe(): Promise<ResidentProfile> {
  const response = await client.get("/public/resident/me", { headers: residentHeaders() });
  return response.data.data;
}
export async function residentLogout(): Promise<void> {
  try { await client.post("/public/resident/logout", null, { headers: residentHeaders() }); }
  finally { localStorage.removeItem("jianda_resident_token"); localStorage.removeItem("jianda_resident_profile"); }
}
export async function fetchCommunityPosts(category = "最新", regionCode = "310113102"): Promise<CommunityPost[]> {
  const response = await client.get("/public/community/posts", { params: { category, regionCode } });
  return response.data.data;
}
export async function uploadCommunityMedia(file: File): Promise<CommunityMedia> {
  const form = new FormData(); form.append("file", file);
  const response = await client.post("/public/community/media", form, { headers: residentHeaders() });
  return response.data.data;
}
export async function createCommunityPost(category: string, content: string, mediaIds: number[] = []): Promise<void> {
  await client.post("/public/community/posts", { category, content, mediaIds }, { headers: residentHeaders() });
}
export async function toggleCommunityLike(id: number): Promise<boolean> {
  const response = await client.post(`/public/community/posts/${id}/like`, null, { headers: residentHeaders() });
  return response.data.data.liked;
}
export async function addCommunityComment(id: number, content: string): Promise<void> {
  await client.post(`/public/community/posts/${id}/comments`, { content }, { headers: residentHeaders() });
}
export async function reportCommunityPost(id: number, reason: string): Promise<void> {
  await client.post(`/public/community/posts/${id}/report`, { reason }, { headers: residentHeaders() });
}

export async function fetchAssistantSuggestions(): Promise<string[]> {
  const response = await client.get("/public/assistant/suggestions");
  return response.data.data;
}

export async function fetchAssistantStatus(): Promise<AssistantStatus> {
  const response = await client.get("/public/assistant/status");
  return response.data.data;
}

export async function askAssistant(
  message: string,
  contextSlug?: string,
  regionCode?: string,
): Promise<AssistantReply> {
  try {
    const response = await client.post(
      "/public/assistant/chat",
      {
        message,
        contextSlug: contextSlug || undefined,
        regionCode: regionCode || undefined,
      },
      { headers: residentHeaders() },
    );
    const data = response.data?.data;
    if (
      !data ||
      typeof data.answer !== "string" ||
      !Array.isArray(data.citations) ||
      typeof data.disclaimer !== "string" ||
      !["status", "retrieval", "ai", "general_ai", "community_post"].includes(data.mode)
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
