<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { fetchDetail, fetchItems, fetchServiceDirectory, setFavorite, type PublicItem, type ServiceDirectoryItem } from "../api";
import { saveFavorite } from "../library";
import { Search, ClipboardList, Building2, Users, WifiOff, CalendarClock, FileCheck2, ListChecks, MapPin, Heart, MessageCircleQuestion, ChevronRight, Phone, ExternalLink, ShieldCheck, Flame, Eye, Bell, ThumbsUp } from "lucide-vue-next";
import { activeRegion } from "../region";
import { truncateSummary } from "../content";
import { buildTelephoneHref } from "../utils/contactActions";
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
const directory = ref<ServiceDirectoryItem[]>([]);
const serviceItems = computed(() => items.value.filter((item) => ["养老", "生活服务", "健康", "社区服务", "办事通知"].includes(item.category)));
const institutions = computed(() => ["全部机构", ...new Set(serviceItems.value.map((item) => item.source_name))]);
const filtered = computed(() => serviceItems.value.filter((item) => (!query.value || `${item.title}${item.summary}`.includes(query.value)) && (type.value === "全部类型" || item.category === type.value) && (institution.value === "全部机构" || item.source_name === institution.value) && (audience.value === "全部对象" || (audience.value === "老年人" ? ["养老", "健康"].includes(item.category) : item.category === "生活服务"))));
function fieldValue(detail: any, type: string) {
  return String(detail.fields?.find((field: any) => field.field_type === type)?.field_value || "");
}
function splitCount(value: string) { return value ? value.split(/[、，,\n]/).filter(Boolean).length : 0; }
async function load() {
  loading.value = true; error.value = "";
  try {
    [items.value, directory.value] = await Promise.all([
      fetchItems(undefined, activeRegion.value.region_code),
      fetchServiceDirectory(activeRegion.value.region_code),
    ]);
    const guides = serviceItems.value;
    const resolved = await Promise.all(guides.map(async (item) => {
      try {
        const detail = await fetchDetail(item.slug);
        const steps = Array.isArray(detail.generated?.STEP_CARDS) ? detail.generated.STEP_CARDS.length : 0;
        return [item.id, {
          item,
          audience: fieldValue(detail, "TARGET_AUDIENCE") || fieldValue(detail, "ELIGIBILITY"),
          deadline: fieldValue(detail, "END_DATE") || String(item.deadline_at || "").slice(0, 10),
          location: fieldValue(detail, "LOCATION"),
          materialCount: splitCount(fieldValue(detail, "MATERIAL")),
          stepCount: steps,
        }] as const;
      } catch {
        return [item.id, { item, audience: "", deadline: "", location: "", materialCount: 0, stepCount: 0 }] as const;
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
watch(() => activeRegion.value.region_code, load);
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main services-page">
  <header class="app-section-head"><ClipboardList /><div><h1>办事行动中心</h1><p>先确认是否适用，再准备材料、地点和办理步骤。</p></div></header>
  <RouterLink class="trusted-service-entry" to="/trusted-services"><ShieldCheck/><span><b>已核验合作服务</b><small>助餐、陪诊、家政和适老维修；与政府公开信息分开展示</small></span><ChevronRight/></RouterLink>
  <label class="search-input"><Search /><input v-model="query" placeholder="搜索办事事项" /></label>
  <section class="local-service-directory"><header class="stream-heading"><div><h2>长辈常用服务</h2><p>只展示已发布内容中的真实地点、电话和官方来源</p></div></header><div class="local-service-directory__grid"><article v-for="service in directory" :key="service.id"><span>{{ service.service_type }}</span><h3>{{ service.name }}</h3><p>{{ truncateSummary(service.description) }}</p><dl><div v-if="service.address"><dt><MapPin/>地址</dt><dd>{{ service.address }}</dd></div><div v-if="service.phone"><dt><Phone/>电话</dt><dd><a :href="buildTelephoneHref(service.phone)">{{ service.phone }}</a></dd></div><div v-if="service.opening_hours"><dt><CalendarClock/>时间</dt><dd>{{ service.opening_hours }}</dd></div></dl><a :href="service.source_url" target="_blank" rel="noopener noreferrer"><ExternalLink/>查看官方来源</a><small v-if="service.last_verified_at">核验于 {{ String(service.last_verified_at).slice(0,10) }}</small></article></div><p v-if="!loading && !directory.length" class="compact-empty">当前没有同时满足“已发布、可追溯、属于大场镇”的服务目录信息，平台不会补写未知电话或地址。</p></section>
  <section class="service-filters" aria-label="办事筛选"><label><Users />服务对象<select v-model="audience"><option>全部对象</option><option>老年人</option><option>居民家庭</option></select></label><label><ClipboardList />事项类型<select v-model="type"><option>全部类型</option><option>养老</option><option>健康</option><option>生活服务</option></select></label><label><Building2 />发布机构<select v-model="institution"><option v-for="item in institutions" :key="item">{{ item }}</option></select></label></section>
  <div class="service-status-legend"><span>即将截止请优先办理</span><span>期限、地点和电话只展示原文已明确的信息</span><b>{{ filtered.length }} 个事项</b></div>
  <section class="service-action-list"><div v-if="loading" class="list-skeleton"><i v-for="n in 3" :key="n"></i></div><div v-else-if="error && !items.length" class="empty"><WifiOff /><b>办事内容暂时无法读取</b><p>{{ error }}</p><button class="green-link" @click="load">重新加载</button></div><template v-else>
    <article v-for="item in filtered" :key="item.id" class="service-action-card">
      <header><span>{{ item.category }}</span><small>{{ item.source_name }}</small><em v-if="(item.hot_score || 0) >= 100" class="hot-badge"><Flame />热门</em><button type="button" :aria-label="favoriteIds.has(item.id) ? '取消收藏' : '收藏事项'" :class="{ active: favoriteIds.has(item.id) }" @click="toggleFavorite(item)"><Heart /></button></header>
      <RouterLink :to="`/guide/${item.slug}`"><h2>{{ item.title }}</h2><p>{{ truncateSummary(item.summary) }}</p></RouterLink>
      <dl>
        <div v-if="details[item.id]?.audience"><dt><Users />适用对象</dt><dd>{{ details[item.id]?.audience }}</dd></div>
        <div v-if="details[item.id]?.deadline"><dt><CalendarClock />办理期限</dt><dd>{{ details[item.id]?.deadline }}</dd></div>
        <div v-if="details[item.id]?.location"><dt><MapPin />办理地点</dt><dd>{{ details[item.id]?.location }}</dd></div>
      </dl>
      <div v-if="details[item.id]?.materialCount || details[item.id]?.stepCount" class="service-card-counts"><span v-if="details[item.id]?.materialCount"><FileCheck2 />{{ details[item.id]?.materialCount }} 项材料</span><span v-if="details[item.id]?.stepCount"><ListChecks />{{ details[item.id]?.stepCount }} 个步骤</span></div>
      <div v-if="(item.view_count||0)+(item.like_count||0)+(item.favorite_count||0)+(item.reminder_count||0) > 0" class="service-card-metrics">
        <span v-if="item.view_count"><Eye />{{ item.view_count }}</span>
        <span v-if="item.like_count"><ThumbsUp />{{ item.like_count }}</span>
        <span v-if="item.favorite_count"><Heart />{{ item.favorite_count }}</span>
        <span v-if="item.reminder_count"><Bell />{{ item.reminder_count }}</span>
      </div>
      <footer><RouterLink :to="{ path: '/assistant', query: { mode: 'context', slug: item.slug } }"><MessageCircleQuestion />询问这个事项</RouterLink><RouterLink :to="`/guide/${item.slug}`">查看办理指南<ChevronRight /></RouterLink></footer>
    </article>
    <div v-if="!filtered.length" class="empty"><Search /><b>没有符合条件的事项</b><p>请减少筛选条件后再试。</p></div>
  </template></section>
</main><BottomNav /></div></template>
