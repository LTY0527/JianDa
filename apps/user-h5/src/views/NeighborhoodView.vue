<script setup lang="ts">
import { onMounted, ref } from "vue";
import { UsersRound, MapPin, WifiOff } from "lucide-vue-next";
import BottomNav from "../components/BottomNav.vue";
import H5Header from "../components/H5Header.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { activeRegion } from "../region";

const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const all = await fetchItems(undefined, activeRegion.value.region_code);
    items.value = all.filter((item) => item.region_code === activeRegion.value.region_code || item.is_local);
  } catch {
    error.value = "暂时无法读取本地社区内容";
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template><div class="h5-page"><H5Header/><main class="h5-main">
  <header class="stream-heading"><div><h1><UsersRound/>邻里</h1><p><MapPin/>上海市 · 宝山区 · {{ activeRegion.street_or_town }}</p></div></header>
  <div v-if="loading" class="compact-empty">正在读取本地消息……</div>
  <div v-else-if="error" class="home-error"><WifiOff/><div><b>邻里消息加载失败</b><p>{{ error }}</p></div><button @click="load">重新加载</button></div>
  <section v-else-if="items.length" class="home-stream"><ContentCard v-for="item in items" :key="item.id" :item="item" actions/></section>
  <div v-else class="compact-empty">大场镇暂时没有已审核发布的邻里消息。平台不会用演示内容冒充真实社区信息。</div>
</main><BottomNav/></div></template>
