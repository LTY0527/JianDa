<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { Search, ClipboardList, Building2, Users, WifiOff } from "lucide-vue-next";
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
const query = ref("");
const audience = ref("全部对象");
const type = ref("全部类型");
const institution = ref("全部机构");
const serviceItems = computed(() => items.value.filter((item) => ["养老", "生活服务", "健康"].includes(item.category)));
const institutions = computed(() => ["全部机构", ...new Set(serviceItems.value.map((item) => item.source_name))]);
const filtered = computed(() => serviceItems.value.filter((item) => (!query.value || `${item.title}${item.summary}`.includes(query.value)) && (type.value === "全部类型" || item.category === type.value) && (institution.value === "全部机构" || item.source_name === institution.value) && (audience.value === "全部对象" || (audience.value === "老年人" ? ["养老", "健康"].includes(item.category) : item.category === "生活服务"))));
onMounted(async () => { try { items.value = await fetchItems(); } catch { error.value = "无法连接办事内容服务，请稍后重试。"; } finally { loading.value = false; } });
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main services-page">
  <header class="app-section-head"><ClipboardList /><div><h1>办事专区</h1><p>筛选适合您的事项，查看材料、地点和办理步骤。</p></div></header>
  <label class="search-input"><Search /><input v-model="query" placeholder="搜索办事事项" /></label>
  <section class="service-filters" aria-label="办事筛选"><label><Users />服务对象<select v-model="audience"><option>全部对象</option><option>老年人</option><option>居民家庭</option></select></label><label><ClipboardList />事项类型<select v-model="type"><option>全部类型</option><option>养老</option><option>健康</option><option>生活服务</option></select></label><label><Building2 />发布机构<select v-model="institution"><option v-for="item in institutions" :key="item">{{ item }}</option></select></label></section>
  <div class="service-status-legend"><span>正在办理</span><span>长期有效</span><b>{{ filtered.length }} 个事项</b></div>
  <section class="feed-surface"><div v-if="loading" class="list-skeleton"><i v-for="n in 3" :key="n"></i></div><div v-else-if="error" class="empty"><WifiOff /><b>办事内容暂时无法读取</b><p>{{ error }}</p></div><template v-else><ContentCard v-for="item in filtered" :key="item.id" :item="item" kind="guide" actions /><div v-if="!filtered.length" class="empty"><Search /><b>没有符合条件的事项</b><p>请减少筛选条件后再试。</p></div></template></section>
</main><BottomNav /></div></template>