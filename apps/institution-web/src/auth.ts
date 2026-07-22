export interface CurrentUser {
  id: number;
  organizationId: number;
  username: string;
  displayName: string;
  role: "PLATFORM_ADMIN" | "ORG_ADMIN" | "REVIEWER";
  organizationName: string;
}

export function currentUser(): CurrentUser | null {
  try {
    const value = localStorage.getItem("jianda_user_info");
    return value ? (JSON.parse(value) as CurrentUser) : null;
  } catch {
    return null;
  }
}

export function isPlatformAdmin(): boolean {
  return currentUser()?.role === "PLATFORM_ADMIN";
}
