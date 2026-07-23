<script setup lang="ts">
import { computed } from "vue";
import { ArrowLeft, HeartHandshake, Settings, Type } from "lucide-vue-next";
import { useRoute } from "vue-router";
import { useSafeBack } from "../../composables/useSafeBack";

const props = withDefaults(
  defineProps<{ title?: string; transparent?: boolean }>(),
  { title: "", transparent: false },
);
const route = useRoute();
const title = computed(() => props.title || String(route.meta.title || "简达"));
const showBack = computed(() => Boolean(route.meta.showBack));
const { goBack } = useSafeBack();
</script>

<template>
  <header
    class="app-top-bar"
    :class="{ 'app-top-bar--transparent': transparent }"
  >
    <button
      v-if="showBack"
      class="app-top-bar__back"
      type="button"
      aria-label="返回"
      @click="goBack"
    >
      <ArrowLeft aria-hidden="true" />
    </button>
    <RouterLink v-else to="/" class="app-top-bar__brand" aria-label="简达首页">
      <span><HeartHandshake aria-hidden="true" /></span><b>简达</b>
    </RouterLink>
    <strong class="app-top-bar__title">{{ showBack ? title : "" }}</strong>
    <nav class="app-top-bar__actions" aria-label="阅读工具">
      <RouterLink to="/settings" aria-label="字号设置"><Type /><span>大字</span></RouterLink>
      <RouterLink to="/settings" aria-label="阅读设置"><Settings /><span>设置</span></RouterLink>
    </nav>
  </header>
</template>