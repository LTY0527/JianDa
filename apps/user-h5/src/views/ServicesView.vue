<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { fetchDetail, fetchItems, setFavorite, type PublicItem } from "../api";
import { saveFavorite } from "../library";
import { Search, ClipboardList, Building2, Users, WifiOff, CalendarClock, FileCheck2, ListChecks, MapPin, Heart, MessageCircleQuestion, ChevronRight } from "lucide-vue-next";
interface GuideSummary {
  item: PublicItem;
  audience: string;
  deadline: string;
  location: string;
  materialCount: number;
  stepCount: number;
}
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
const query = ref("");
const audience = ref("全部对象");
const type = ref("全部类型");
const institution = ref("全部机构");
const details = ref<Record<number, GuideSummary>>({});
const favoriteIds = ref(new Set<number>());
const serviceItems = computed(() => items.value.filter((item) => ["养老", "生活服务", "健康"].includes(item.category)));
const institutions = computed(() => ["全部机构", ...new Set(serviceItems.value.map((item) => item.source_name))]);
const filtered = computed(() => serviceItems.value.filter((item) => (!query.value || `${item.title}${item.summary}`.includes(query.value)) && (type.value === "全部类型" || item.category === type.value) && (institution.value === "全部机构" || item.source_name === institution.value) && (audience.value === "全部对象" || (audience.value === "老年人" ? ["养老", "健康"].includes(item.category) : item.category === "生活服务"))));
function fieldValue(detail: any, type: string) {
  return String(detail.fields?.find((field: any) => field.field_type === type)?.field_value || "");
}
function splitCount(value: string) { return value ? value.split(/[、，,\n]/).filter(Boolean).length : 0; }
async function load() {
  loading.value = true; error.value = "";
  try {
    items.value = await fetchItems();
    const guides = items.value.filter((item) => ["养老", "生活服务", "健康"].includes(item.category));
    const resolved = await Promise.all(guides.map(async (item) => {
      try {
        const detail = await fetchDetail(item.slug);
        const steps = Array.isArray(detail.generated?.STEP_CARDS) ? detail.generated.STEP_CARDS.length : 0;
        return [item.id, {
          item,
          audience: fieldValue(detail, "AUDIENCE") || "以详情中的适用条件为准",
          deadline: fieldValue(detail, "DEADLINE") || "长期有效",
          location: fieldValue(detail, "LOCATION") || "以详情公布地点为准",
          materialCount: splitCount(fieldValue(detail, "MATERIAL")),
          stepCount: steps,
        }] as const;
      } catch {
        return [item.id, { item, audience: "请查看事项详情", deadline: "以最新通知为准", location: "请查看事项详情", materialCount: 0, stepCount: 0 }] as const;
      }
    }));
    details.value = Object.fromEntries(resolved);
    favoriteIds.value = new Set(guides.filter((item) => localStorage.getItem(`favorite_${item.id}`) === "1").map((item) => item.id));
  } catch { error.value = "无法连接办事内容服务，请稍后重试。"; }
  finally { loading.value = false; }
}
async function toggleFavorite(item: PublicItem) {
  const next = !favoriteIds.value.has(item.id);
  try {
    await setFavorite(item.id, next);
    const updated = new Set(favoriteIds.value);
    next ? updated.add(item.id) : updated.delete(item.id);
    favoriteIds.value = updated;
    localStorage.setItem(`favorite_${item.id}`, next ? "1" : "0");
    saveFavorite(item, next);
  } catch { error.value = "收藏操作失败，请稍后重试。"; }
}
onMounted(load);
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main services-page">
  <header class="app-section-head"><ClipboardList /><div><h1>办事行动中心</h1><p>先确认是否适用，再准备材料、地点和办理步骤。</p></div></header>
  <label class="search-input"><Search /><input v-model="query" placeholder="搜索办事事项" /></label>
  <section class="service-filters" aria-label="办事筛选"><label><Users />服务对象<select v-model="audience"><option>全部对象</option><option>老年人</option><option>居民家庭</option></select></label><label><ClipboardList />事项类型<select v-model="type"><option>全部类型</option><option>养老</option><option>健康</option><option>生活服务</option></select></label><label><Building2 />发布机构<select v-model="institution"><option v-for="item in institutions" :key="item">{{ item }}</option></select></label></section>
  <div class="service-status-legend"><span>即将截止请优先办理</span><span>长期有效也请核对最新要求</span><b>{{ filtered.length }} 个事项</b></div>
  <section class="service-action-list"><div v-if="loading" class="list-skeleton"><i v-for="n in 3" :key="n"></i></div><div v-else-if="error && !items.length" class="empty"><WifiOff /><b>办事内容暂时无法读取</b><p>{{ error }}</p><button class="green-link" @click="load">重新加载</button></div><template v-else>
    <article v-for="item in filtered" :key="item.id" class="service-action-card">
      <header><span>{{ item.category }}</span><small>{{ item.source_name }}</small><button type="button" :aria-label="favoriteIds.has(item.id) ? '取消收藏' : '收藏事项'" :class="{ active: favoriteIds.has(item.id) }" @click="toggleFavorite(item)"><Heart /></button></header>
      <RouterLink :to="`/guide/${item.slug}`"><h2>{{ item.title }}</h2><p>{{ item.summary }}</p></RouterLink>
      <dl>
        <div><dt><Users />适用对象</dt><dd>{{ details[item.id]?.audience || "正在核对…" }}</dd></div>
        <div><dt><CalendarClock />办理期限</dt><dd>{{ details[item.id]?.deadline || "正在核对…" }}</dd></div>
        <div><dt><MapPin />办理地点</dt><dd>{{ details[item.id]?.location || "正在核对…" }}</dd></div>
      </dl>
      <div class="service-card-counts"><span><FileCheck2 />{{ details[item.id]?.materialCount || 0 }} 项材料</span><span><ListChecks />{{ details[item.id]?.stepCount || 0 }} 个步骤</span></div>
      <footer><RouterLink :to="{ path: '/assistant', query: { about: item.slug } }"><MessageCircleQuestion />问问这个事项</RouterLink><RouterLink :to="`/guide/${item.slug}`">查看办理指南<ChevronRight /></RouterLink></footer>
    </article>
    <div v-if="!filtered.length" class="empty"><Search /><b>没有符合条件的事项</b><p>请减少筛选条件后再试。</p></div>
  </template></section>
</main><BottomNav /></div></template>
