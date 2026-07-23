import { computed, type MaybeRefOrGetter, toValue } from "vue";
import { useRoute, useRouter } from "vue-router";

export function useSafeBack(options: {
  fallback?: MaybeRefOrGetter<string | undefined>;
  isDirty?: MaybeRefOrGetter<boolean>;
  confirmMessage?: string;
} = {}) {
  const route = useRoute();
  const router = useRouter();
  const fallback = computed(
    () => toValue(options.fallback) || String(route.meta.backTo || "/"),
  );

  async function goBack() {
    if (
      toValue(options.isDirty) &&
      !window.confirm(options.confirmMessage || "当前修改尚未保存，确定离开吗？")
    ) {
      return;
    }
    const historyBack = window.history.state?.back;
    if (typeof historyBack === "string" && historyBack.startsWith("/")) {
      router.back();
      return;
    }
    await router.replace(fallback.value);
  }

  return { fallback, goBack };
}