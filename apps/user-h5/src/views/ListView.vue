<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems } from "../api";
import { activeRegion } from "../region";
import { MessageCircleQuestion, Search, WifiOff } from "lucide-vue-next";
const route = useRoute();
const router = useRouter();
const query = ref(String(route.query.q || ""));
const items = ref<any[]>([]);
const loading = ref(true);
const error = ref("");
const title = computed(() => route.path === "/search" ? "搜索" : String(route.params.name || "全部内容"));
const stateKey = computed(() => `jianda_list_${route.path}`);
const filtered = computed(() => items.value.filter((item) => !query.value || item.title.includes(query.value) || item.summary.includes(query.value)));
watch(query, (value) => router.replace({ query: value ? { q: value } : {} }));
async function load() {
  loading.value = true;
  error.value = "";
  try {
    items.value = await fetchItems(
      title.value === "搜索" || title.value === "全部内容" ? undefined : title.value,
      activeRegion.value.region_code,
    );
  } catch {
    error.value = "网络连接失败，请检查服务后重试";
  } finally {
    loading.value = false;
  }
}
onMounted(async () => {
  const saved = sessionStorage.getItem(stateKey.value);
  if (saved && !route.query.q) query.value = JSON.parse(saved).query || "";
  await load();
  requestAnimationFrame(() => window.scrollTo(0, Number(saved ? JSON.parse(saved).scroll : 0)));
});
watch(() => activeRegion.value.region_code, load);
onUnmounted(() => sessionStorage.setItem(stateKey.value, JSON.stringify({ query: query.value, scroll: window.scrollY })));
</script>
<template>
  <div class="h5-page">
    <AppTopBar :title="title" />
    <main class="h5-main list-page">
      <div class="simple-head list-heading"><h1>{{ title }}</h1><p>只展示经过审核的可靠公共服务信息。</p></div>
      <label class="search-input"><Search /><input v-model="query" :autofocus="route.path === '/search'" placeholder="输入您想了解的内容" /></label>
      <p class="result-count">找到 {{ filtered.length }} 条可靠信息</p>
      <section class="list-surface">
        <div v-if="loading" class="list-skeleton"><i v-for="n in 4" :key="n"></i></div>
        <ContentCard v-for="item in filtered" v-else :key="item.id" :item="item" />
        <div v-if="!loading && !filtered.length" class="empty"><component :is="error ? WifiOff : Search" /><b>{{ error ? "内容暂时无法读取" : "平台资料暂未命中" }}</b><p>{{ error || "可以换一个更简单的关键词，或让简达助手继续从已审核内容中查找。" }}</p><button v-if="error" class="green-link" @click="$router.go(0)">重新加载</button><RouterLink v-else class="green-link" :to="{ path: '/assistant', query: { q: query } }"><MessageCircleQuestion />带关键词问简达</RouterLink></div>
      </section>
    </main>
    <BottomNav />
  </div>
</template>
