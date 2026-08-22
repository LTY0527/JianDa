import { http, type ApiResponse } from "./http";
export interface ModerationPost { id: number; content: string; status: "REPORTED"|"HIDDEN"; created_at: string; nickname: string; report_count: number; }
export const communityAdminApi = {
  posts: () => http.get<ApiResponse<ModerationPost[]>>("/community-admin/posts"),
  status: (id: number, status: "VISIBLE"|"HIDDEN") => http.post<ApiResponse<void>>(`/community-admin/posts/${id}/status`, { status }),
};
