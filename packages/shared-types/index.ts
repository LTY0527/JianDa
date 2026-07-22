export type Role = "PLATFORM_ADMIN" | "ORG_ADMIN" | "REVIEWER";
export type DocumentStatus =
  | "UPLOADED"
  | "EXTRACTING"
  | "PROCESSING"
  | "WAITING_REVIEW"
  | "REVIEWED"
  | "PUBLISHED"
  | "FAILED"
  | "WITHDRAWN";
export interface StaffUser {
  id: number;
  organizationId: number;
  username: string;
  displayName: string;
  role: Role;
}
export interface DocumentItem {
  id: number;
  title: string;
  fileName: string;
  organizationName: string;
  status: DocumentStatus;
  progress: number;
  updatedAt: string;
}
export interface PublishedItem {
  id: number;
  slug: string;
  title: string;
  summary: string;
  category: string;
  sourceName: string;
  publishedAt: string;
  favorite?: boolean;
}
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}
