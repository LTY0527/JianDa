<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { importanceScore, isFavorite } from "../content";
import { activeRegion } from "../region";
import { Newspaper, Search, RefreshCw, WifiOff } from "lucide-vue-next";
const channels = ["推荐", "健康", "养老政策", "防诈", "社区服务", "文化学习"];
const channelMap: Record<string,string[]> = {
  养老政策: ["养老", "养老政策"],
  防诈: ["反诈", "防诈"],
  社区服务: ["生活服务", "社区服务", "时政"],
  文化学习: ["文化", "文化学习"],
};
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
const channel = ref("推荐");
const mode = ref("最新");
const query = ref("");
const visible = ref(6);
const filtered = computed(() => {
  let result = items.value.filter((item) => channel.value === "推荐" || (channelMap[channel.value] || [channel.value]).includes(item.category));
  if (query.value.trim()) result = result.filter((item) => `${item.title}${item.summary}${item.source_name}${item.category}`.includes(query.value.trim()));
  if (mode.value === "已收藏") result = result.filter((item) => isFavorite(item.id));
  return [...result].sort((a,b) => mode.value === "重要" ? importanceScore(b) - importanceScore(a) : String(b.published_at).localeCompare(String(a.published_at)));
});
let lastLoadedAt = 0;
let loadPromise: Promise<void> | null = null;
function load(): Promise<void> {
  if (loadPromise) return loadPromise;
  loadPromise = (async () => {
    loading.value = true; error.value = "";
    try { items.value = await fetchItems(undefined, activeRegion.value.region_code); }
    catch { error.value = "无法连接权威内容服务，请稍后重试。"; }
    finally { loading.value = false; lastLoadedAt = Date.now(); }
  })();
  void loadPromise.finally(() => { loadPromise = null; });
  return loadPromise;
}
function refreshWhenResumed() {
  if (document.visibilityState === "visible" && Date.now() - lastLoadedAt >= 5_000) void load();
}
onMounted(() => {
  window.addEventListener("focus", refreshWhenResumed);
  document.addEventListener("visibilitychange", refreshWhenResumed);
  void load();
});
onBeforeUnmount(() => {
  window.removeEventListener("focus", refreshWhenResumed);
  document.removeEventListener("visibilitychange", refreshWhenResumed);
});
watch(() => activeRegion.value.region_code, load);
</script>
<template>
  <div class="h5-page"><AppTopBar />
    <main class="h5-main feed-page">
      <header class="app-section-head"><Newspaper /><div><h1>权威资讯</h1><p>持续浏览经过人工审核的政策、健康和生活信息。</p></div><button class="icon-action" type="button" aria-label="刷新资讯" @click="load"><RefreshCw /></button></header>
      <label class="search-input"><Search /><input v-model="query" placeholder="搜索标题、摘要或来源" /></label>
      <nav class="channel-tabs" aria-label="资讯频道"><button v-for="item in channels" :key="item" :class="{ active: channel === item }" @click="channel = item; visible = 6">{{ item }}</button></nav>
      <div class="filter-tabs"><button v-for="item in ['最新','重要','已收藏']" :key="item" :class="{ active: mode === item }" @click="mode = item">{{ item }}</button><span>共 {{ filtered.length }} 条</span></div>
      <section class="feed-surface">
        <div v-if="loading" class="list-skeleton"><i v-for="n in 4" :key="n"></i></div>
        <div v-else-if="error" class="empty"><WifiOff /><b>资讯暂时无法读取</b><p>{{ error }}</p><button class="green-link" @click="load">重新加载</button></div>
        <template v-else><ContentCard v-for="item in filtered.slice(0, visible)" :key="item.id" :item="item" kind="news" actions /><div v-if="!filtered.length" class="empty"><Search /><b>没有符合条件的资讯</b><p>换一个频道或搜索词试试。</p></div><button v-if="visible < filtered.length" class="load-more" @click="visible += 6">加载更多</button></template>
      </section>
    </main><BottomNav />
  </div>
</template>
