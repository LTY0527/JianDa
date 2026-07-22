import { http, type ApiResponse } from "./http";

export interface DocumentRow {
  id: number;
  title: string;
  file_name?: string;
  organization_name: string;
  status: string;
  progress: number;
  updated_at: string;
}

export interface ExtractedField {
  id: number;
  field_label: string;
  field_value: string;
  page_no: number;
  source_quote: string;
  confidence: number;
  review_status: string;
}

export interface DocumentDetail {
  id: number;
  title: string;
  raw_text?: string;
  page_count?: number;
  category?: string;
  import_url?: string;
  source_published_at?: string;
  organization_name: string;
  processing_status: string;
}

export interface GeneratedContent {
  id: number;
  content_type: string;
  title: string;
  content_json?: string;
  plain_text?: string;
  status: string;
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<ApiResponse<{ token: string; user: Record<string, unknown> }>>(
      "/auth/login",
      { username, password },
    ),
  me: () => http.get<ApiResponse<Record<string, unknown>>>("/auth/me"),
};

export const documentApi = {
  list: () => http.get<ApiResponse<DocumentRow[]>>("/documents"),
  create: (title: string) =>
    http.post<ApiResponse<{ id: number }>>("/documents", { title }),
  upload: (id: number, file: File, manualText?: string) => {
    const body = new FormData();
    body.append("file", file);
    if (manualText) body.append("manualText", manualText);
    return http.post<ApiResponse<Record<string, unknown>>>(
      `/documents/${id}/upload`,
      body,
    );
  },
  process: (id: number) =>
    http.post<ApiResponse<{ status: string; progress: number }>>(
      `/documents/${id}/process`,
    ),
  detail: (id: number) =>
    http.get<ApiResponse<DocumentDetail>>(`/documents/${id}`),
  fields: (id: number) =>
    http.get<ApiResponse<ExtractedField[]>>(`/documents/${id}/fields`),
  generated: (id: number) =>
    http.get<ApiResponse<GeneratedContent[]>>(`/documents/${id}/generated`),
  updateField: (
    documentId: number,
    fieldId: number,
    value: string,
    confirmed: boolean,
  ) =>
    http.put<ApiResponse<void>>(`/documents/${documentId}/fields/${fieldId}`, {
      value,
      confirmed,
    }),
  review: (id: number, comment = "字段与原文一致") =>
    http.post<ApiResponse<void>>(`/documents/${id}/review`, { comment }),
  publish: (
    id: number,
    payload: {
      title: string;
      category: string;
      sourceName: string;
      sourceUrl?: string;
    },
  ) =>
    http.post<ApiResponse<{ slug: string }>>(
      `/documents/${id}/publish`,
      payload,
    ),
  withdraw: (id: number) =>
    http.post<ApiResponse<void>>(`/documents/${id}/withdraw`),
};
