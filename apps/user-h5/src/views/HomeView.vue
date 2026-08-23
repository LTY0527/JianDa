<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems, type PublicItem } from "../api";
import { contentKind, importanceScore, normalizeTitle, truncateSummary } from "../content";
import { readerPreferences } from "../library";
import { Search, Landmark, HeartPulse, HandHeart, ShieldAlert, Drama, ChevronRight, Volume2, Type, CalendarDays, BellRing, ArrowRight, WifiOff, Utensils, Building2, PhoneCall } from "lucide-vue-next";
import { articleCover, categoryDefaultCover, hasRealCover, realCoverScore } from "../utils/coverImage";
import { activeRegion } from "../region";
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const error = ref("");
const featuredCoverFailed = ref(false);
const cats = [["健康", HeartPulse, "健康"], ["养老政策", Landmark, "养老政策"], ["防诈", ShieldAlert, "防诈"], ["社区服务", HandHeart, "社区服务"], ["文化学习", Drama, "文化学习"]] as const;
const today = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "long" }).format(new Date());
const hour = new Date().getHours();
const greeting = hour < 11 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
function homePriority(item: PublicItem) {
  const preferred = readerPreferences().channels.map((value) => value === "政策" ? "养老政策" : value === "生活" ? "社区服务" : value);
  return importanceScore(item) + (preferred.includes(item.category) ? 15 : 0);
}
const todayReads = computed(() => [...items.value]
  .filter((item) => contentKind(item) === "news")
  .sort((a, b) => {
    const aPriority = homePriority(a);
    const bPriority = homePriority(b);
    const bandDifference = Math.floor(bPriority / 10) - Math.floor(aPriority / 10);
    if (bandDifference) return bandDifference;
    return (bPriority + realCoverScore(b)) - (aPriority + realCoverScore(a));
  }).slice(0, 7));
const featured = computed(() => todayReads.value[0]);
const featuredHasVisual = computed(() => Boolean(featured.value && hasRealCover(featured.value) && !featuredCoverFailed.value));
const alerts = computed(() => [...items.value]
  .filter((item) => item.id !== featured.value?.id && ["反诈", "健康", "生活服务", "社区服务"].includes(item.category))
  .sort((a,b) => importanceScore(b)-importanceScore(a)).slice(0,2));
const usedPrimaryIds = computed(() => new Set([featured.value?.id, ...alerts.value.map((item) => item.id)].filter(Boolean)));
const newsStream = computed(() => todayReads.value.filter((item) => !usedPrimaryIds.value.has(item.id)).slice(0,4));
const usedNewsIds = computed(() => new Set([...usedPrimaryIds.value, ...newsStream.value.map((item) => item.id)]));
const guides = computed(() => items.value.filter((item) => item.is_local || item.region_code === activeRegion.value.region_code).slice(0,3));
const usedAllIds = computed(() => new Set([...usedNewsIds.value, ...guides.value.map((item) => item.id)]));
const healthReminders = computed(() => items.value.filter((item) => !usedAllIds.value.has(item.id) && item.category === "健康").slice(0,3));
const fraudReminders = computed(() => items.value.filter((item) => !usedAllIds.value.has(item.id) && item.category === "反诈").slice(0,3));
const commonServices = [["社区卫生", HeartPulse, "健康"], ["长者食堂", Utensils, "养老"], ["社区事务", Building2, "社区服务"], ["便民电话", PhoneCall, "生活服务"]] as const;
function fallbackCover(event: Event, item: PublicItem) {
  const image = event.currentTarget as HTMLImageElement;
  const attempt = Number(image.dataset.fallbackAttempt || "0") + 1;
  image.dataset.fallbackAttempt = String(attempt);
  const fallback = categoryDefaultCover(item, attempt);
  if (!image.src.endsWith(fallback)) image.src = fallback;
}
async function load() { loading.value = true; error.value = ""; featuredCoverFailed.value = false; try { items.value = await fetchItems(undefined, activeRegion.value.region_code); } catch { error.value = "暂时无法读取权威内容，请稍后再试"; } finally { loading.value = false; } }
onMounted(load);
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main home-main">
  <section class="welcome welcome--compact"><div><p class="welcome-date"><CalendarDays />{{ today }}</p><h1>{{ greeting }}，{{ activeRegion.street_or_town }}居民</h1><p>{{ loading ? "正在整理今天的信息" : `今天有 ${Math.min(items.length, 3)} 件事值得留意` }}</p></div><RouterLink to="/search" class="search-box"><Search />搜索通知、办事和社区服务</RouterLink><div class="home-shortcuts"><RouterLink to="/settings"><Type /><span><b>大字阅读</b><small>18—24px 可调</small></span></RouterLink><RouterLink to="/listen"><Volume2 /><span><b>听一听</b><small>把权威内容读给您听</small></span></RouterLink></div></section>
  <div v-if="loading" class="home-skeleton" aria-label="正在加载"><i v-for="n in 4" :key="n"></i></div>
  <div v-else-if="error" class="home-error" role="status"><WifiOff /><div><b>内容暂时没有加载成功</b><p>{{ error }}</p></div><button type="button" @click="load">重新加载</button></div>
  <template v-else>
    <header v-if="featured" class="stream-heading home-recommend-heading"><div><h2>今天要紧的事</h2><p>按本地相关、重要程度和截止时间整理</p></div></header>
    <section v-if="featured" class="featured-story" :class="{ 'featured-story--text': !featuredHasVisual }">
      <img v-if="featuredHasVisual" :src="articleCover(featured)" :alt="featured.image_alt_text || `${featured.title}配图`" fetchpriority="high" decoding="async" referrerpolicy="no-referrer" @error="featuredCoverFailed = true"/>
      <div><span>{{featured.category}}{{featured.is_local?" · 本地":""}}</span><h2>{{normalizeTitle(featured.title)}}</h2><p>{{truncateSummary(featured.summary)}}</p><small>权威来源 · {{featured.source_name}} · {{String(featured.published_at).slice(0,10)}} · {{featured.reading_minutes||1}}分钟阅读</small><RouterLink :to="`/news/${featured.slug}`">查看适老版<ArrowRight/></RouterLink></div>
    </section>
    <section v-if="alerts.length" class="important-alerts"><header><BellRing /><div><h2>重要提醒</h2><p>请优先留意安全、健康和公共服务变化</p></div></header><article v-for="alert in alerts" :key="alert.id"><span>{{ alert.category }}</span><div><h3>{{ normalizeTitle(alert.title) }}</h3><p>{{ truncateSummary(alert.summary, 100) }}</p><small>{{ alert.source_name }} · {{ String(alert.published_at).slice(0,10) }}</small></div><RouterLink :to="`/${contentKind(alert)}/${alert.slug}`">立即查看<ArrowRight /></RouterLink></article></section>
    <section class="service-brief"><header class="stream-heading"><div><h2>大场通知</h2><p>只展示已审核发布、与当前地区相关的内容</p></div><RouterLink to="/services">查看办事<ChevronRight /></RouterLink></header><div class="service-brief__grid"><RouterLink v-for="item in guides" :key="item.id" :to="`/${contentKind(item)}/${item.slug}`"><span>{{ item.category }}</span><h3>{{ normalizeTitle(item.title) }}</h3><p>{{ truncateSummary(item.summary, 90) }}</p><small>{{ item.source_name }}</small><b>查看详情<ArrowRight /></b></RouterLink></div><div v-if="!guides.length" class="compact-empty">当前没有已审核发布的大场镇通知。</div></section>
    <section class="common-services"><header class="stream-heading"><div><h2>长辈常用</h2><p>按现实任务进入服务目录</p></div></header><nav><RouterLink v-for="service in commonServices" :key="service[0]" :to="{ path: '/services', query: { type: service[2] } }"><component :is="service[1]"/><span>{{ service[0] }}</span><ChevronRight/></RouterLink></nav></section>
    <section class="home-stream"><header class="stream-heading"><div><h2>最近更新</h2><p>来自权威来源并已通过人工审核</p></div><RouterLink to="/news">更多内容<ChevronRight /></RouterLink></header><ContentCard v-for="item in newsStream" :key="item.id" :item="item" actions /></section>
    <section v-if="healthReminders.length || fraudReminders.length" class="home-topic-grid">
      <article v-if="healthReminders.length"><header><HeartPulse/><div><h2>健康提醒</h2><p>来自已审核权威内容</p></div></header><RouterLink v-for="item in healthReminders" :key="item.id" :to="`/news/${item.slug}`"><span><b>{{ normalizeTitle(item.title) }}</b><small>{{ item.source_name }} · {{ String(item.published_at).slice(0,10) }}</small></span><ChevronRight/></RouterLink></article>
      <article v-if="fraudReminders.length"><header><ShieldAlert/><div><h2>防诈提醒</h2><p>先核实，再操作</p></div></header><RouterLink v-for="item in fraudReminders" :key="item.id" :to="`/news/${item.slug}`"><span><b>{{ normalizeTitle(item.title) }}</b><small>{{ item.source_name }} · {{ String(item.published_at).slice(0,10) }}</small></span><ChevronRight/></RouterLink></article>
    </section>
    <section class="home-categories"><header class="stream-heading"><div><h2>按分类查看</h2><p>快速找到关心的公共服务内容</p></div></header><nav class="categories" aria-label="快捷频道"><RouterLink v-for="c in cats" :key="c[0]" :to="`/category/${c[2]}`"><span><component :is="c[1]" /></span><b>{{ c[0] }}</b></RouterLink><RouterLink class="all-channel" to="/news"><span><ChevronRight /></span><b>全部资讯</b></RouterLink></nav></section>
    <RouterLink class="home-all-news" to="/news">查看全部权威资讯<ChevronRight /></RouterLink>
  </template>
</main><BottomNav /></div></template>
