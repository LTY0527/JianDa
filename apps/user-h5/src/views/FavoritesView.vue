<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { favoriteItems } from "../library";
import { contentKind } from "../content";
import { Heart } from "lucide-vue-next";
const available = ref<PublicItem[]>([]);
const filter = ref("全部");
const revision = ref(0);
function refresh(){ revision.value += 1; }
const favorites = computed(() => { revision.value; const ids = new Set(favoriteItems().map((item) => item.id)); return available.value.filter((item) => ids.has(item.id)).filter((item) => filter.value === "全部" || (filter.value === "办事" ? contentKind(item) === "guide" : contentKind(item) === "news")); });
onMounted(async () => { window.addEventListener("jianda-library-change", refresh); try { available.value = await fetchItems(); } catch { available.value = []; } });
onUnmounted(() => window.removeEventListener("jianda-library-change", refresh));
</script>
<template><div class="h5-page"><AppTopBar title="我的收藏" /><main class="h5-main library-page"><header class="library-head"><div><h1>我的收藏</h1><p>只显示仍在公开的内容，撤回内容会自动移除。</p></div></header><nav class="filter-tabs"><button v-for="item in ['全部','资讯','办事']" :key="item" :class="{ active: filter === item }" @click="filter=item">{{ item }}</button></nav><section v-if="favorites.length" class="feed-surface"><ContentCard v-for="item in favorites" :key="item.id" :item="item" actions /></section><div v-else class="empty"><Heart /><b>还没有符合条件的收藏</b><p>在资讯或办事详情点击“收藏”，就能在这里找到。</p><RouterLink to="/news" class="green-link">去看看资讯</RouterLink></div></main><BottomNav /></div></template>