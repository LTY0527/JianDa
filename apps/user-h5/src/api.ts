import axios from "axios";

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080/api",
  timeout: 10000,
});
const anonymousUser =
  localStorage.getItem("jianda_anonymous_user") || crypto.randomUUID();
localStorage.setItem("jianda_anonymous_user", anonymousUser);
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
