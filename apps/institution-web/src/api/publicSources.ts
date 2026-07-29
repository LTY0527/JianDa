import { http, type ApiResponse } from "./http";

export interface PublicSource {
  id: number;
  source_name: string;
  source_type: string;
  source_url: string;
  publisher: string;
  whitelist_status: string;
  enabled: boolean;
  last_imported_at?: string;
  notes?: string;
}

export interface FixtureContent {
  fixtureId: string;
  title: string;
  sourceName: string;
  sourceType: string;
  sourceUrl: string;
  publisher: string;
  publishedAt: string;
  body: string;
  category: string;
}

export interface ImportRecord {
  id: number;
  title: string;
  status: string;
  category: string;
  import_method: string;
  import_url: string;
  source_published_at: string;
  imported_at: string;
  source_name: string;
  source_type?: string;
  failure_reason?: string;
}

export interface ManualImportPayload {
  sourceId: number;
  title: string;
  sourceName: string;
  sourceType: string;
  sourceUrl: string;
  publisher: string;
  publishedAt: string;
  body: string;
  category: string;
}

export interface WebArticlePreview {
  title: string;
  source_name: string;
  published_at?: string;
  author?: string;
  cover_image_url?: string;
  cover_image_type:
    | "ORIGINAL_COVER"
    | "ARTICLE_IMAGE"
    | "CATEGORY_DEFAULT"
    | "AI_ILLUSTRATION";
  canonical_url: string;
  content_preview: string;
  content_kind: string;
  authority_level: string;
  robots_allowed: boolean;
  robots_status: string;
  warnings: string[];
  image_alt_text?: string;
  image_width?: number;
  image_height?: number;
  image_source_name?: string;
  image_source_url?: string;
  image_cached: boolean;
  image_license_note?: string;
}

export interface ImageCandidate {
  id: number;
  document_id: number;
  candidate_url: string;
  source_page_url: string;
  source_name?: string;
  alt_text?: string;
  width?: number;
  height?: number;
  mime_type?: string;
  image_hash?: string;
  image_cached: boolean;
  discovery_method: "OPEN_GRAPH" | "JSON_LD" | "ARTICLE_IMAGE";
  priority_rank: number;
  rights_status: string;
  review_status: string;
  rejection_reason?: string;
  usage_basis?: string;
}

export interface WebSourceRegistry {
  id: number;
  domain: string;
  source_name: string;
  source_type: string;
  authority_level: string;
  enabled: boolean;
  discovery_mode: string;
  homepage_url: string;
  rss_url?: string;
  sitemap_url?: string;
  section_url?: string;
  daily_crawl_time: string;
  max_articles_per_run: number;
  allow_image_candidates: boolean;
  allow_auto_ai: boolean;
  daily_article_budget: number;
  daily_token_budget: number;
  last_crawled_at?: string;
  last_status: string;
  next_run_at?: string;
  last_error?: string;
}

export interface SourceRegistryPayload {
  name: string;
  domain: string;
  type: string;
  authorityLevel: string;
  homepageUrl: string;
  rssUrl: string;
  sitemapUrl: string;
  sectionUrl: string;
  discoveryMode: string;
  dailyCrawlTime: string;
  maxArticlesPerRun: number;
  allowImageCandidates: boolean;
  allowAutoAi: boolean;
  dailyArticleBudget: number;
  dailyTokenBudget: number;
}

export interface CrawlJobError {
  id: number;
  crawl_job_id: number;
  source_registry_id: number;
  failed_url?: string;
  processing_stage: string;
  error_code: string;
  error_summary: string;
  retryable: boolean;
  retry_count: number;
  next_retry_at?: string;
  resolved_at?: string;
  created_at: string;
  updated_at: string;
}

export interface CrawlJob {
  id: number;
  source_registry_id: number;
  document_id?: number;
  source_name: string;
  domain: string;
  original_url: string;
  canonical_url?: string;
  status: "PENDING" | "RUNNING" | "SUCCESS" | "PARTIAL_SUCCESS" | "FAILED" | "CANCELLED" | "DISABLED";
  trigger_type: string;
  processing_stage: string;
  discovered_at?: string;
  started_at?: string;
  finished_at?: string;
  discovered_count: number;
  added_count: number;
  duplicate_count: number;
  skipped_count: number;
  failed_count: number;
  retry_count: number;
  last_error?: string;
  lock_owner?: string;
  created_by?: number;
  scheduler_identity?: string;
  errors?: CrawlJobError[];
}

export const publicSourceApi = {
  sources: () => http.get<ApiResponse<PublicSource[]>>("/public-sources"),
  createSource: (payload: {
    name: string;
    type: string;
    url: string;
    publisher: string;
    notes: string;
  }) => http.post<ApiResponse<PublicSource>>("/public-sources", payload),
  setEnabled: (id: number, enabled: boolean) =>
    http.put<ApiResponse<null>>(`/public-sources/${id}/enabled`, { enabled }),
  fixtures: () =>
    http.get<ApiResponse<FixtureContent[]>>("/public-sources/fixtures"),
  importFixture: (fixtureId: string) =>
    http.post<ApiResponse<{ documentId: number }>>(
      `/public-sources/import/fixture/${fixtureId}`,
    ),
  importManual: (payload: ManualImportPayload) =>
    http.post<ApiResponse<{ documentId: number }>>(
      "/public-sources/import/manual",
      payload,
    ),
  imports: () =>
    http.get<ApiResponse<ImportRecord[]>>("/public-sources/imports"),
  preview: (documentId: number) =>
    http.get<ApiResponse<Record<string, unknown>>>(
      `/public-sources/imports/${documentId}`,
    ),
  process: (documentId: number) =>
    http.post<ApiResponse<{ status: string }>>(
      `/public-sources/imports/${documentId}/process`,
    ),
  previewWebArticle: (url: string) =>
    http.post<ApiResponse<WebArticlePreview>>("/web-articles/preview", { url }),
  importWebArticle: (url: string) =>
    http.post<ApiResponse<{ documentId: number; imageReviewRequired: boolean }>>(
      "/web-articles/import",
      { url },
    ),
  confirmWebCover: (documentId: number) =>
    http.post<ApiResponse<null>>(`/web-articles/${documentId}/cover/confirm`),
  useCategoryDefaultCover: (documentId: number) =>
    http.post<ApiResponse<null>>(`/web-articles/${documentId}/cover/category-default`),
  selectArticleCover: (documentId: number, imageUrl: string) =>
    http.post<ApiResponse<null>>(`/web-articles/${documentId}/cover/article-image`, {
      imageUrl,
    }),
  imageCandidates: (documentId: number) =>
    http.get<ApiResponse<ImageCandidate[]>>(`/web-articles/${documentId}/image-candidates`),
  approveImageCandidate: (candidateId: number, sourceName: string, usageBasis: string) =>
    http.post<ApiResponse<null>>(`/web-articles/image-candidates/${candidateId}/approve`, { sourceName, usageBasis }),
  rejectImageCandidate: (candidateId: number, reason: string) =>
    http.post<ApiResponse<null>>(`/web-articles/image-candidates/${candidateId}/reject`, { reason }),
  recrawlWebArticle: (documentId: number) =>
    http.post<ApiResponse<{ documentId: number; contentKind: string }>>(
      `/web-articles/${documentId}/recrawl`,
    ),
  webRegistries: () =>
    http.get<ApiResponse<WebSourceRegistry[]>>("/source-registries"),
  createWebRegistry: (payload: SourceRegistryPayload) =>
    http.post<ApiResponse<WebSourceRegistry>>("/source-registries", payload),
  updateWebRegistry: (id: number, payload: SourceRegistryPayload) =>
    http.put<ApiResponse<WebSourceRegistry>>(`/source-registries/${id}`, payload),
  setWebRegistryEnabled: (id: number, enabled: boolean) =>
    http.put<ApiResponse<WebSourceRegistry>>(`/source-registries/${id}/enabled`, { enabled }),
  crawlJobs: (params?: { status?: string; sourceId?: number }) =>
    http.get<ApiResponse<CrawlJob[]>>("/crawl-tasks", { params }),
  crawlJob: (jobId: number) =>
    http.get<ApiResponse<CrawlJob>>(`/crawl-tasks/${jobId}`),
  stopCrawlJob: (jobId: number) =>
    http.post<ApiResponse<null>>(`/crawl-tasks/${jobId}/cancel`),
  retryCrawlError: (errorId: number) =>
    http.post<ApiResponse<{ jobId: number }>>(`/crawl-tasks/errors/${errorId}/retry`),
  retryCrawlFailures: (jobId: number) =>
    http.post<ApiResponse<{ jobIds: number[]; count: number }>>(`/crawl-tasks/${jobId}/retry-failures`),
};
