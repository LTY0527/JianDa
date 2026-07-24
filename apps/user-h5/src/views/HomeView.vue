<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { contentKind, importanceScore } from "../content";
import { readerPreferences } from "../library";
import { Search, Landmark, HeartPulse, HandHeart, ShieldAlert, Wrench, Drama, ChevronRight, Volume2, Type, CalendarDays, BellRing, ArrowRight, WifiOff } from "lucide-vue-next";
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
const cats = [["政策", Landmark, "时政"], ["健康", HeartPulse, "健康"], ["养老", HandHeart, "养老"], ["反诈", ShieldAlert, "反诈"], ["生活", Wrench, "生活服务"], ["文化", Drama, "文化"]] as const;
const today = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "long" }).format(new Date());
const hour = new Date().getHours();
const greeting = hour < 11 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
const alerts = computed(() => [...items.value].filter((item) => ["反诈", "健康", "生活服务"].includes(item.category)).sort((a,b) => importanceScore(b)-importanceScore(a)).slice(0,2));
const todayReads = computed(() => { const preferred = readerPreferences().channels.map((item) => item === "政策" ? "时政" : item === "生活" ? "生活服务" : item); return [...items.value].filter((item) => contentKind(item) === "news").sort((a,b) => (importanceScore(b) + (preferred.includes(b.category) ? 15 : 0)) - (importanceScore(a) + (preferred.includes(a.category) ? 15 : 0))).slice(0,3); });
const guides = computed(() => items.value.filter((item) => ["养老", "生活服务", "健康"].includes(item.category)).slice(0,2));
async function load() { loading.value = true; error.value = ""; try { items.value = await fetchItems(); } catch { error.value = "暂时无法读取权威内容，请稍后再试"; } finally { loading.value = false; } }
onMounted(load);
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main home-main">
  <section class="welcome welcome--compact"><div><p class="welcome-date"><CalendarDays />{{ today }}</p><h1>{{ greeting }}，今天想了解什么？</h1><p>权威内容先审核，再为您清楚说明。</p></div><RouterLink to="/search" class="search-box"><Search />搜索办事指南、健康资讯</RouterLink><div class="home-shortcuts"><RouterLink to="/settings"><Type /><span><b>大字阅读</b><small>18—24px 可调</small></span></RouterLink><RouterLink to="/settings"><Volume2 /><span><b>语音设置</b><small>慢速也能听清</small></span></RouterLink></div></section>
  <nav class="categories" aria-label="快捷频道"><RouterLink v-for="c in cats" :key="c[0]" :to="`/category/${c[2]}`"><span><component :is="c[1]" /></span><b>{{ c[0] }}</b></RouterLink><RouterLink class="all-channel" to="/news"><span><ChevronRight /></span><b>全部频道</b></RouterLink></nav>
  <div v-if="loading" class="home-skeleton" aria-label="正在加载"><i v-for="n in 4" :key="n"></i></div>
  <div v-else-if="error" class="home-error" role="status"><WifiOff /><div><b>内容暂时没有加载成功</b><p>{{ error }}</p></div><button type="button" @click="load">重新加载</button></div>
  <template v-else>
    <section v-if="alerts.length" class="important-alerts"><header><BellRing /><div><h2>重要提醒</h2><p>请优先留意安全、健康和公共服务变化</p></div></header><article v-for="alert in alerts" :key="alert.id"><span>{{ alert.category }}</span><div><h3>{{ alert.title }}</h3><p>{{ alert.summary }}</p><small>{{ alert.source_name }} · {{ String(alert.published_at).slice(0,10) }}</small></div><RouterLink :to="`/${contentKind(alert)}/${alert.slug}`">立即查看<ArrowRight /></RouterLink></article></section>
    <section class="home-stream"><header class="stream-heading"><div><h2>今日必看</h2><p>按重要程度、新鲜度和已读状态排序</p></div><RouterLink to="/news">进入资讯<ChevronRight /></RouterLink></header><ContentCard v-for="item in todayReads" :key="item.id" :item="item" actions /></section>
    <section class="service-brief"><header class="stream-heading"><div><h2>办事快报</h2><p>材料、地点和步骤，提前看清楚</p></div><RouterLink to="/services">全部事项<ChevronRight /></RouterLink></header><div class="service-brief__grid"><RouterLink v-for="item in guides" :key="item.id" :to="`/guide/${item.slug}`"><span>{{ item.category }}</span><h3>{{ item.title }}</h3><p>{{ item.summary }}</p><small>{{ item.source_name }}</small><b>查看怎么做<ArrowRight /></b></RouterLink></div><div v-if="!guides.length" class="compact-empty">当前没有已发布办事事项</div></section>
    <RouterLink class="home-all-news" to="/news">查看全部权威资讯<ChevronRight /></RouterLink>
  </template>
</main><BottomNav /></div></template>
