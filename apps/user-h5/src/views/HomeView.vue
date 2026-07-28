<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { contentKind, importanceScore, normalizeTitle, truncateSummary } from "../content";
import { readerPreferences } from "../library";
import { Search, Landmark, HeartPulse, HandHeart, ShieldAlert, Drama, ChevronRight, Volume2, Type, CalendarDays, BellRing, ArrowRight, WifiOff } from "lucide-vue-next";
import { articleCover, categoryDefaultCover } from "../utils/coverImage";
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
const cats = [["健康", HeartPulse, "健康"], ["养老政策", Landmark, "养老政策"], ["防诈", ShieldAlert, "防诈"], ["社区服务", HandHeart, "社区服务"], ["文化学习", Drama, "文化学习"]] as const;
const today = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "long" }).format(new Date());
const hour = new Date().getHours();
const greeting = hour < 11 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
const alerts = computed(() => [...items.value].filter((item) => ["反诈", "健康", "生活服务"].includes(item.category)).sort((a,b) => importanceScore(b)-importanceScore(a)).slice(0,2));
const todayReads = computed(() => { const preferred = readerPreferences().channels.map((item) => item === "政策" ? "养老政策" : item === "生活" ? "社区服务" : item); return [...items.value].filter((item) => contentKind(item) === "news").sort((a,b) => (importanceScore(b) + (preferred.includes(b.category) ? 15 : 0)) - (importanceScore(a) + (preferred.includes(a.category) ? 15 : 0))).slice(0,7); });
const featured = computed(() => todayReads.value[0]);
const newsStream = computed(() => todayReads.value.slice(1, 5));
const guides = computed(() => items.value.filter((item) => item.content_kind === "SERVICE_NOTICE" || contentKind(item) === "guide").slice(0,3));
function fallbackCover(event: Event, item: PublicItem) {
  const image = event.currentTarget as HTMLImageElement;
  const fallback = categoryDefaultCover(item);
  if (!image.src.endsWith(fallback)) image.src = fallback;
}
async function load() { loading.value = true; error.value = ""; try { items.value = await fetchItems(); } catch { error.value = "暂时无法读取权威内容，请稍后再试"; } finally { loading.value = false; } }
onMounted(load);
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main home-main">
  <section class="welcome welcome--compact"><div><p class="welcome-date"><CalendarDays />{{ today }}</p><h1>{{ greeting }}，今天想了解什么？</h1><p>权威内容先审核，再为您清楚说明。</p></div><RouterLink to="/search" class="search-box"><Search />搜索办事指南、健康资讯</RouterLink><div class="home-shortcuts"><RouterLink to="/settings"><Type /><span><b>大字阅读</b><small>18—24px 可调</small></span></RouterLink><RouterLink to="/settings"><Volume2 /><span><b>语音设置</b><small>慢速也能听清</small></span></RouterLink></div></section>
  <div v-if="loading" class="home-skeleton" aria-label="正在加载"><i v-for="n in 4" :key="n"></i></div>
  <div v-else-if="error" class="home-error" role="status"><WifiOff /><div><b>内容暂时没有加载成功</b><p>{{ error }}</p></div><button type="button" @click="load">重新加载</button></div>
  <template v-else>
    <header v-if="featured" class="stream-heading home-recommend-heading"><div><h2>今日推荐</h2><p>从权威资讯中为您优先选择</p></div></header>
    <section v-if="featured" class="featured-story">
      <img :src="articleCover(featured)" :alt="featured.image_alt_text || `${featured.title}配图`" fetchpriority="high" decoding="async" referrerpolicy="no-referrer" @error="fallbackCover($event, featured)"/>
      <div><span>{{featured.category}}{{featured.is_local?" · 本地":""}}</span><h2>{{normalizeTitle(featured.title)}}</h2><p>{{truncateSummary(featured.summary)}}</p><small>{{featured.source_name}} · {{String(featured.published_at).slice(0,10)}} · {{featured.reading_minutes||1}}分钟阅读</small><RouterLink :to="`/news/${featured.slug}`">查看适老版<ArrowRight/></RouterLink></div>
    </section>
    <section v-if="alerts.length" class="important-alerts"><header><BellRing /><div><h2>重要提醒</h2><p>请优先留意安全、健康和公共服务变化</p></div></header><article v-for="alert in alerts" :key="alert.id"><span>{{ alert.category }}</span><div><h3>{{ normalizeTitle(alert.title) }}</h3><p>{{ truncateSummary(alert.summary, 100) }}</p><small>{{ alert.source_name }} · {{ String(alert.published_at).slice(0,10) }}</small></div><RouterLink :to="`/${contentKind(alert)}/${alert.slug}`">立即查看<ArrowRight /></RouterLink></article></section>
    <section class="home-stream"><header class="stream-heading"><div><h2>图文资讯</h2><p>按人工置顶、重要程度和发布时间排序</p></div><RouterLink to="/news">更多内容<ChevronRight /></RouterLink></header><ContentCard v-for="item in newsStream" :key="item.id" :item="item" actions /></section>
    <section class="service-brief"><header class="stream-heading"><div><h2>重要公共服务通知</h2><p>时间、地点和办理要求，提前看清楚</p></div><RouterLink to="/services">全部通知<ChevronRight /></RouterLink></header><div class="service-brief__grid"><RouterLink v-for="item in guides" :key="item.id" :to="`/${contentKind(item)}/${item.slug}`"><span>{{ item.category }}</span><h3>{{ normalizeTitle(item.title) }}</h3><p>{{ truncateSummary(item.summary, 90) }}</p><small>{{ item.source_name }}</small><b>查看怎么做<ArrowRight /></b></RouterLink></div><div v-if="!guides.length" class="compact-empty">当前没有已发布公共服务通知</div></section>
    <section class="home-categories"><header class="stream-heading"><div><h2>按分类查看</h2><p>快速找到关心的公共服务内容</p></div></header><nav class="categories" aria-label="快捷频道"><RouterLink v-for="c in cats" :key="c[0]" :to="`/category/${c[2]}`"><span><component :is="c[1]" /></span><b>{{ c[0] }}</b></RouterLink><RouterLink class="all-channel" to="/news"><span><ChevronRight /></span><b>全部资讯</b></RouterLink></nav></section>
    <RouterLink class="home-all-news" to="/news">查看全部权威资讯<ChevronRight /></RouterLink>
  </template>
</main><BottomNav /></div></template>
