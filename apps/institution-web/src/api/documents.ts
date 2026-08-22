import { http, type ApiResponse } from "./http";

export interface DocumentRow {
  id: number;
  title: string;
  file_name?: string;
  source_type?: string;
  source_name?: string;
  original_published_at?: string;
  category?: string;
  content_kind?: string;
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
  duplicate_suspected?: boolean;
}

export interface DocumentDetail {
  id: number;
  title: string;
  raw_text?: string;
  page_count?: number;
  extraction_method?: "pymupdf" | "ocr" | "pymupdf+ocr" | "manual" | "manual_required" | "unknown";
  category?: string;
  import_url?: string;
  source_published_at?: string;
  organization_name: string;
  source_name?: string;
  original_filename?: string;
  mime_type?: string;
  file_size?: number;
  file_sha256?: string;
  source_type?: string;
  canonical_url?: string;
  source_authority_level?: string;
  original_published_at?: string;
  cover_image_url?: string;
  cover_image_type?: string;
  image_source_name?: string;
  image_source_url?: string;
  image_alt_text?: string;
  image_cached?: boolean;
  image_license_note?: string;
  image_reviewed?: boolean;
  original_html?: string;
  extracted_text?: string;
  original_page_available?: boolean;
  content_kind?: string;
  version_root_id?: number;
  previous_version_id?: number;
  version_no?: number;
  old_content_hash?: string;
  new_content_hash?: string;
  content_change_summary?: string;
  processing_status: string;
}

export interface MetadataPreview {
  title: string;
  source_name: string;
  document_number: string;
  source_type: string;
  authority_status: "DOCUMENT_EVIDENCE" | "UNCONFIRMED" | "CONFLICT";
  confidence: number;
  evidence_quote: string;
  evidence_type: "HEADER" | "SIGNATURE" | "SEAL" | "PUBLISHER_FIELD" | "FILENAME" | "NONE";
  page_no: number;
  warnings: string[];
}

export interface GeneratedContent {
  id: number;
  content_type: string;
  title: string;
  content_json?: string;
  plain_text?: string;
  status: string;
}

export interface DocumentSegment {
  id: number;
  page_no: number;
  segment_no: number;
  text: string;
}

export interface ProcessingJob {
  id: number;
  status: string;
  progress: number;
  error_message?: string;
  stage?: string;
  last_failed_stage?: string;
  provider_request_id?: string;
  reason_code?: string;
  provider_id?: string;
  model_id?: string;
  response_fingerprint?: string;
  crossed_provider_boundary?: boolean;
  fact_checkpoint_json?: string;
  retry_count?: number;
  cache_hit?: boolean;
  total_ms?: number;
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
  started_at?: string;
  finished_at?: string;
  updated_at?: string;
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
  create: (title: string, sourceName = "", metadata?: MetadataPreview | null) =>
    http.post<ApiResponse<{ id: number }>>("/documents", {
      title,
      sourceName,
      documentNumber: metadata?.document_number,
      sourceType: metadata?.source_type,
      authorityStatus: metadata?.authority_status,
      confidence: metadata?.confidence,
      evidenceQuote: metadata?.evidence_quote,
      evidenceType: metadata?.evidence_type,
      pageNo: metadata?.page_no,
    }),
  metadataPreview: (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return http.post<ApiResponse<MetadataPreview>>("/documents/metadata-preview", body);
  },
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
    http.post<ApiResponse<{
      documentId: number;
      jobId: number;
      status: string;
      stage: string;
      progress: number;
      alreadyRunning?: boolean;
    }>>(
      `/documents/${id}/process`,
    ),
  retryRewrite: (id: number) =>
    http.post<ApiResponse<{ status: string; progress: number }>>(
      `/documents/${id}/retry-rewrite`,
    ),
  detail: (id: number) =>
    http.get<ApiResponse<DocumentDetail>>(`/documents/${id}`),
  fields: (id: number) =>
    http.get<ApiResponse<ExtractedField[]>>(`/documents/${id}/fields`),
  generated: (id: number) =>
    http.get<ApiResponse<GeneratedContent[]>>(`/documents/${id}/generated`),
  segments: (id: number) =>
    http.get<ApiResponse<DocumentSegment[]>>(`/documents/${id}/segments`),
  originalFile: (id: number) =>
    http.get<Blob>(`/documents/${id}/original-file`, { responseType: "blob" }),
  originalFileUrl: (id: number, download = false) => {
    const base = String(import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");
    return `${base}/documents/${id}/original-file${download ? "?download=true" : ""}`;
  },
  originalFileHeaders: (): Record<string, string> => {
    const token = localStorage.getItem("jianda_token");
    return token ? { Authorization: `Bearer ${token}` } : {};
  },
  uploadCover: (id: number, file: File) => {
    const body = new FormData();
    body.append("file", file);
    return http.post<ApiResponse<{ filename: string }>>(
      `/documents/${id}/cover`,
      body,
    );
  },
  jobs: (id: number) =>
    http.get<ApiResponse<ProcessingJob[]>>(`/documents/${id}/jobs`),
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
      allowPublicOriginal?: boolean;
    },
  ) =>
    http.post<ApiResponse<{ slug: string }>>(
      `/documents/${id}/publish`,
      payload,
    ),
  withdraw: (id: number) =>
    http.post<ApiResponse<void>>(`/documents/${id}/withdraw`),
};
