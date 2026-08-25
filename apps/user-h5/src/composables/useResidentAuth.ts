import { readonly, ref } from "vue";
import type { Router } from "vue-router";
import { residentMe, residentLogout as apiLogout } from "../api";

export type ResidentAuthStatus = "unknown" | "authenticated" | "guest";

const status = ref<ResidentAuthStatus>("unknown");
const profile = ref<{ id: number; username: string; nickname: string; district: string; streetOrTown: string; regionCode: string; demo: boolean } | null>(null);
let bootstrapPromise: Promise<void> | null = null;
let routerRef: Router | null = null;

function clearSession() {
  localStorage.removeItem("jianda_resident_token");
  localStorage.removeItem("jianda_resident_profile");
  profile.value = null;
  status.value = "guest";
}

function setAuthenticated(p: typeof profile.value) {
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
      }
    }
  })();
  return bootstrapPromise;
}

export function onUnauthorized() {
  clearSession();
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
    await router.replace("/resident/login");
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
  };
}
