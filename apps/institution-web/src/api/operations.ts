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
}

export const operationMetricsApi = {
  current: () => http.get<ApiResponse<OperationMetrics>>("/operation-metrics"),
};
