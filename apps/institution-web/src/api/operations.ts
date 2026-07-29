import { http, type ApiResponse } from "./http";

export interface OperationMetrics {
  authoritySourceCount: number;
  discoveredArticleCount: number;
  successfulCrawlCount: number;
  duplicateCount: number;
  waitingReviewCount: number;
  publishedCount: number;
  failedCount: number;
  averageProcessingMs: number;
  aiRequestCount: number;
  aiTokenCount: number;
  aiSuccessRate: number;
  viewCount: number;
  favoriteCount: number;
  assistantQueryCount: number;
  citedAnswerRate: number;
  manualEditRate: number;
  todayDiscoveredCount: number;
  todayCollectedCount: number;
  todayDuplicateCount: number;
  todayFailedCount: number;
  pendingImageCandidateCount: number;
  averageCrawlMs: number;
  averageAiMs: number;
  tokenBudgetTotal: number;
  tokenUsedToday: number;
  sources: OperationSourceStatus[];
  aiQueueByStatus: OperationAiQueueStatus[];
  recentErrors: OperationError[];
}

export interface OperationSourceStatus {
  id: number;
  source_name: string;
  domain: string;
  enabled: boolean;
  last_status: string;
  last_crawled_at?: string;
  next_run_at?: string;
  last_error?: string;
  failure_count: number;
}

export interface OperationAiQueueStatus {
  status: string;
  item_count: number;
  estimated_tokens: number;
  actual_tokens: number;
}

export interface OperationError {
  id: number;
  source_name: string;
  error_code: string;
  error_summary: string;
  processing_stage: string;
  failed_url?: string;
  retryable: boolean;
  retry_count: number;
  created_at: string;
}

export const operationMetricsApi = {
  current: () => http.get<ApiResponse<OperationMetrics>>("/operation-metrics"),
};
