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

export interface CrawlJob {
  id: number;
  document_id?: number;
  source_name: string;
  domain: string;
  original_url: string;
  status: string;
  content_changed: boolean;
  last_success_at?: string;
  last_error?: string;
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
  crawlJobs: () =>
    http.get<ApiResponse<CrawlJob[]>>("/web-articles/jobs"),
  stopCrawlJob: (jobId: number) =>
    http.post<ApiResponse<null>>(`/web-articles/jobs/${jobId}/stop`),
};
