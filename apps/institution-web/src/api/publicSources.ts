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
  original_domain?: string;
  canonical_domain?: string;
  canonical_cross_domain?: boolean;
  canonical_confirmation_required?: boolean;
  trust_status?: "VERIFIED" | "UNVERIFIED";
  external_source_verified?: boolean;
  registered_source?: Pick<WebSourceRegistry, "id" | "source_name" | "source_type" | "enabled" | "region_code" | "street_or_town">;
  source_type_suggestion?: string;
  images?: Array<Record<string, unknown>>;
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
  context_text?: string;
  relevance_score?: number;
  priority_rank: number;
  rights_status: string;
  review_status: string;
  rejection_reason?: string;
  usage_basis?: string;
}

export interface WebSourceRegistry {
  id: number;
  domain: string;
  allowed_hosts?: string;
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
  allow_image_cache?: boolean;
  allow_image_candidates: boolean;
  allow_auto_crawl?: boolean;
  allow_auto_ai: boolean;
  requires_manual_review?: boolean;
  daily_article_budget: number;
  daily_token_budget: number;
  schedule_mode: "DAILY" | "INTERVAL";
  interval_hours: number;
  schedule_timezone: string;
  recent_days: 1 | 3 | 7 | 30;
  include_keywords?: string;
  exclude_keywords?: string;
  auto_save_draft: boolean;
  duplicate_strategy: "SKIP" | "CREATE_VERSION";
  max_retries: number;
  image_usage_policy: string;
  image_usage_basis?: string;
  auto_approve_images: boolean;
  image_cache_allowed: boolean;
  last_crawled_at?: string;
  last_status: string;
  next_run_at?: string;
  last_error?: string;
  street_or_town?: string;
  region_code?: string;
}

export interface AiQueueItem {
  id: number;
  source_registry_id?: number;
  source_name?: string;
  document_id: number;
  status:
    | "QUEUED"
    | "WAITING_APPROVAL"
    | "WAITING_BUDGET"
    | "PROCESSING"
    | "SUCCEEDED"
    | "FAILED"
    | "DUPLICATE";
  reason_code?: string;
  reason_summary?: string;
  estimated_tokens?: number;
  available_at?: string;
  estimated_recovery_at?: string;
  created_at?: string;
}

export interface RuntimeCapabilities {
  llmProvider: string;
  externalModel: string;
  assistantExternalEnabled: boolean;
  crawlAutoAiEnabled: boolean;
  crawlSchedulerEnabled: boolean;
  dailyArticleLimit: number;
  dailyTokenLimit: number;
  amap: RuntimeCapability;
  webSearch: RuntimeCapability & { provider: string };
  payment: { available: boolean; provider: string; message: string; testEnvironment?: boolean };
  aiService: {
    service: RuntimeCapability;
    llm: RuntimeCapability & { provider?: string; model?: string };
    ocr: RuntimeCapability & { engine?: string; required_languages?: string[]; available_languages?: string[] };
    webCollector: RuntimeCapability;
  };
}

export interface RuntimeCapability {
  status: "ready" | "degraded" | "disabled" | "unreachable";
  message?: string;
}

export interface SourceRegistryPayload {
  name: string;
  domain: string;
  allowedHosts: string;
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
  scheduleMode: "DAILY" | "INTERVAL";
  intervalHours: number;
  scheduleTimezone: string;
  recentDays: 1 | 3 | 7 | 30;
  includeKeywords: string;
  excludeKeywords: string;
  autoSaveDraft: boolean;
  duplicateStrategy: "SKIP" | "CREATE_VERSION";
  maxRetries: number;
  imageUsagePolicy: string;
  imageUsageBasis: string;
  autoApproveImages: boolean;
  imageCacheAllowed: boolean;
}

export interface ArticleDiscoveryCandidate {
  discovered_url: string;
  canonical_url: string;
  title?: string;
  published_time?: string;
  discovery_method: string;
  discovery_page?: string;
  content_kind_candidate?: string;
  dedup_key: string;
  imported?: boolean;
  duplicate?: boolean;
  has_previous_version?: boolean;
  relevance_level?: "HIGH" | "MEDIUM" | "LOW";
  relevance_score?: number;
  recommended_topic?: string;
  recommendation_reason?: string;
  region_code?: string;
  region_name?: string;
  has_real_image?: boolean;
}

export interface QuickSourcePreview {
  original_url: string;
  canonical_url: string;
  domain: string;
  https: boolean;
  page_title: string;
  source_name?: string;
  wechat_account_name?: string;
  account_subject?: string;
  wechat_biz?: string;
  wechat_article: boolean;
  source_identity_fingerprint: string;
  source_type_suggestion: string;
  robots_allowed: boolean;
  robots_status: string;
  official_verified: boolean;
  registered_source?: Pick<WebSourceRegistry, "id" | "source_name" | "source_type" | "enabled">;
}

export interface CoverBackfillResult {
  scanned: number;
  updated: number;
  candidatesCreated: number;
  autoApproved: number;
  failed: number;
  errors: Array<{ documentId: number; message: string }>;
}

export interface CoverBackfillJob {
  jobId: number;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "PARTIAL_SUCCESS";
  total: number;
  processed: number;
  updated: number;
  candidatesCreated: number;
  autoApproved: number;
  failed: number;
  currentDocumentId?: number;
  currentDocumentTitle?: string;
  errors: Array<{ documentId: number; message: string }>;
}

export interface ArticleDiscoveryResult {
  sourceId: number;
  method: string;
  candidates: ArticleDiscoveryCandidate[];
  duplicateCount: number;
  errors: string[];
  filtered_external_count?: number;
  filtered_navigation_count?: number;
  filtered_external_domains?: string[];
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
  progress_message?: string;
  discoveryResult?: ArticleDiscoveryResult;
  existing?: boolean;
  errors?: CrawlJobError[];
}

export interface BatchImportJob extends CrawlJob {
  result?: {
    imported: Array<{ documentId: number; aiQueueStatus?: string }>;
    importedCount: number;
    duplicateCount: number;
    failedCount: number;
  };
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
    http.post<ApiResponse<WebArticlePreview>>("/web-articles/preview-any", { url }),
  importWebArticle: (url: string) =>
    http.post<ApiResponse<{ documentId: number; imageReviewRequired: boolean }>>(
      "/web-articles/import",
      { url },
    ),
  importWebArticleOnce: (url: string, canonicalConfirmed: boolean) =>
    http.post<ApiResponse<{
      documentId: number;
      imageReviewRequired: boolean;
      aiQueueStatus: string;
    }>>("/web-articles/import-once", { url, canonicalConfirmed }),
  importPastedWebArticle: (payload: {
    url: string;
    title: string;
    sourceName: string;
    body: string;
    contentKind: string;
  }) =>
    http.post<ApiResponse<{
      documentId: number;
      imageReviewRequired: boolean;
      aiQueueStatus: string;
    }>>("/web-articles/import-pasted", payload),
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
  discoverRegistryArticles: (id: number, payload: {
    method: string;
    entryUrl: string;
    recentDays: number;
    maxArticles: number;
    includeKeywords: string;
    excludeKeywords: string;
    onlyUnimported: boolean;
  }) =>
    http.post<ApiResponse<ArticleDiscoveryResult>>(`/source-registries/${id}/discover`, {
      ...payload,
    }),
  startRegistryDiscoveryJob: (id: number, payload: {
    method: string;
    entryUrl: string;
    recentDays: number;
    maxArticles: number;
    includeKeywords: string;
    excludeKeywords: string;
    onlyUnimported: boolean;
  }) => http.post<ApiResponse<CrawlJob>>(`/source-registries/${id}/discover-jobs`, payload),
  registryDiscoveryJob: (jobId: number) =>
    http.get<ApiResponse<CrawlJob>>(`/source-registries/discover-jobs/${jobId}`),
  shadowRegistryArticle: (id: number, url: string) =>
    http.post<ApiResponse<WebArticlePreview>>(`/source-registries/${id}/shadow`, { url }),
  collectRegistryArticle: (id: number, url: string) =>
    http.post<ApiResponse<{
      documentId: number;
      imageReviewRequired: boolean;
      aiQueueStatus: string;
    }>>(`/source-registries/${id}/collect`, { url }),
  collectRegistryArticles: (id: number, urls: string[]) =>
    http.post<ApiResponse<{
      jobId: number;
      status: "PENDING";
      total: number;
    }>>(`/source-registries/${id}/collect-batch`, { urls }),
  registryImportJob: (jobId: number) =>
    http.get<ApiResponse<BatchImportJob>>(`/source-registries/import-jobs/${jobId}`),
  quickPreviewSource: (url: string) =>
    http.post<ApiResponse<QuickSourcePreview>>("/source-registries/quick-preview", { url }),
  quickConfirmSource: (payload: {
    url: string;
    sourceName: string;
    sourceType: string;
    verificationNote: string;
    officialConfirmed: boolean;
    mode: "TEMPORARY_IMPORT" | "SAVE_TRUSTED" | "SAVE_MANUAL_SCAN" | "SAVE_AUTO_SCAN";
    imageUsagePolicy: string;
    imageUsageBasis: string;
    autoApproveImages: boolean;
    imageCacheAllowed: boolean;
    continueImport: boolean;
  }) => http.post<ApiResponse<{
    source: WebSourceRegistry;
    imported?: { documentId: number; aiQueueStatus: string };
  }>>("/source-registries/quick-confirm", payload),
  previewCoverBackfill: (payload: {
    onlyMissing: boolean;
    sourceId?: number;
    contentKind?: string;
    publishStatus?: string;
    fromDate?: string;
    toDate?: string;
  }) => http.post<ApiResponse<{
    total: number;
    byType: Record<string, number>;
    items: Array<Record<string, unknown>>;
  }>>("/cover-backfill/preview", payload),
  executeCoverBackfill: (payload: {
    onlyMissing: boolean;
    sourceId?: number;
    contentKind?: string;
    publishStatus?: string;
    fromDate?: string;
    toDate?: string;
  }) => http.post<ApiResponse<CoverBackfillResult>>("/cover-backfill/execute", payload),
  startCoverBackfillJob: (payload: {
    onlyMissing: boolean;
    sourceId?: number;
    contentKind?: string;
    publishStatus?: string;
    fromDate?: string;
    toDate?: string;
  }) => http.post<ApiResponse<CoverBackfillJob>>("/cover-backfill/jobs", payload),
  coverBackfillJob: (jobId: number) =>
    http.get<ApiResponse<CoverBackfillJob>>(`/cover-backfill/jobs/${jobId}`),
  retryCoverBackfillItem: (jobId: number, documentId: number) =>
    http.post<ApiResponse<CoverBackfillJob>>(
      `/cover-backfill/jobs/${jobId}/retry/${documentId}`,
    ),
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
  aiQueue: (status?: string) =>
    http.get<ApiResponse<AiQueueItem[]>>("/ai-queue", {
      params: status ? { status } : undefined,
    }),
  approveAiQueue: (queueId: number) =>
    http.post<ApiResponse<Record<string, unknown>>>(`/ai-queue/${queueId}/approve`),
  retryAiQueue: (queueId: number) =>
    http.post<ApiResponse<Record<string, unknown>>>(`/ai-queue/${queueId}/retry`),
  reconcileAiQueue: () =>
    http.post<ApiResponse<{ requeued: number; unchanged: number }>>(
      "/ai-queue/reconcile",
    ),
  runtimeCapabilities: () =>
    http.get<ApiResponse<RuntimeCapabilities>>("/runtime-capabilities"),
};
