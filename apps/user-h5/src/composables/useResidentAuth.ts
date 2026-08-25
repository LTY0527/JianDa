import { readonly, ref } from "vue";
import type { Router } from "vue-router";
import { residentMe, residentLogout as apiLogout } from "../api";

export type ResidentAuthStatus = "unknown" | "authenticated" | "guest";

export interface ResidentProfileShape {
  id: number;
  username: string;
  nickname: string;
  district: string;
  streetOrTown: string;
  regionCode: string;
  demo: boolean;
}

const status = ref<ResidentAuthStatus>("unknown");
const profile = ref<ResidentProfileShape | null>(null);
let bootstrapPromise: Promise<void> | null = null;
let routerRef: Router | null = null;

function clearSession() {
  localStorage.removeItem("jianda_resident_token");
  localStorage.removeItem("jianda_resident_profile");
  profile.value = null;
  status.value = "guest";
}

function setAuthenticated(p: ResidentProfileShape | null) {
  profile.value = p;
  status.value = "authenticated";
  if (p) {
    try {
      localStorage.setItem("jianda_resident_profile", JSON.stringify(p));
    } catch {
      /* ignore */
    }
  }
}

export function invalidateAuthCache() {
  bootstrapPromise = null;
}

export function completeLogin(p: ResidentProfileShape) {
  setAuthenticated(p);
  bootstrapPromise = Promise.resolve();
}

export async function refreshAfterLogin(): Promise<void> {
  invalidateAuthCache();
  await bootstrap(true);
}

export async function bootstrap(force = false): Promise<void> {
  if (!force && bootstrapPromise) return bootstrapPromise;
  const token = localStorage.getItem("jianda_resident_token");
  if (!token) {
    clearSession();
    bootstrapPromise = Promise.resolve();
    return bootstrapPromise;
  }
  bootstrapPromise = (async () => {
    try {
      const me = await residentMe();
      setAuthenticated(me);
    } catch (e: any) {
      const statusCode = e?.response?.status;
      if (statusCode === 401 || statusCode === 403) {
        clearSession();
        invalidateAuthCache();
      } else {
        const cached = localStorage.getItem("jianda_resident_profile");
        if (cached) {
          try {
            profile.value = JSON.parse(cached);
            status.value = "authenticated";
            return;
          } catch {
            /* ignore */
          }
        }
        clearSession();
        invalidateAuthCache();
      }
    }
  })();
  return bootstrapPromise;
}

export function onUnauthorized() {
  clearSession();
  invalidateAuthCache();
  if (routerRef) {
    const current = routerRef.currentRoute.value;
    const redirect = current.fullPath;
    routerRef.replace({
      path: "/resident/login",
      query: redirect && redirect !== "/" ? { redirect } : {},
    });
  }
}

export async function ensureLogin(router: Router, redirectPath?: string): Promise<boolean> {
  routerRef = router;
  await bootstrap();
  if (status.value === "authenticated") return true;
  const redirect = redirectPath ?? router.currentRoute.value.fullPath;
  await router.replace({
    path: "/resident/login",
    query: redirect && redirect !== "/" ? { redirect } : {},
  });
  return false;
}

export async function logout(router: Router) {
  try {
    await apiLogout();
  } catch {
    /* ignore */
  } finally {
    clearSession();
    invalidateAuthCache();
    try {
      await router.replace("/resident/login");
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn("[useResidentAuth] logout navigation error:", e);
      if (window.location.pathname !== "/resident/login") {
        window.location.replace("/resident/login");
      }
    }
  }
}

export function setRouter(router: Router) {
  routerRef = router;
}

export function useResidentAuth() {
  return {
    status: readonly(status),
    profile: readonly(profile),
    bootstrap,
    ensureLogin,
    logout,
    onUnauthorized,
    setRouter,
    completeLogin,
    refreshAfterLogin,
    invalidateAuthCache,
  };
}
