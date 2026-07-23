<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems } from "../api";
import { Search, Landmark, HeartPulse, HandHeart, ShieldAlert, Wrench, Drama, ChevronRight, Volume2, Type, CalendarDays, ArrowRight } from "lucide-vue-next";
const items = ref<any[]>([]);
const loading = ref(true);
const error = ref("");
const cats = [["时政", Landmark], ["健康", HeartPulse], ["养老", HandHeart], ["反诈", ShieldAlert], ["生活服务", Wrench], ["文化", Drama]] as const;
const today = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "long" }).format(new Date());
const hour = new Date().getHours();
const greeting = hour < 11 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
const guides = computed(() => items.value.filter((item) => ["养老", "生活服务"].includes(item.category)));
const news = computed(() => items.value.filter((item) => !["养老", "生活服务"].includes(item.category)));
onMounted(async () => {
  try { items.value = await fetchItems(); }
  catch { error.value = "暂时无法读取权威内容，请稍后再试"; }
  finally { loading.value = false; }
});
</script>
<template>
  <div class="h5-page">
    <H5Header />
    <main class="h5-main home-main">
      <section class="welcome">
        <p class="welcome-date"><CalendarDays />{{ today }}</p>
        <h1>{{ greeting }}，今天想了解什么？</h1>
        <p>权威公共服务信息，经过人工审核后为您清楚说明。</p>
        <RouterLink to="/search" class="search-box"><Search />搜索办事指南、健康资讯</RouterLink>
        <div class="home-shortcuts">
          <RouterLink to="/settings"><Type /><span><b>大字阅读</b><small>18—24px 可调</small></span></RouterLink>
          <RouterLink to="/settings"><Volume2 /><span><b>语音朗读</b><small>慢速也能听清</small></span></RouterLink>
        </div>
      </section>
      <nav class="categories" aria-label="内容分类">
        <RouterLink v-for="c in cats" :key="c[0]" :to="`/category/${c[0]}`"><span><component :is="c[1]" /></span><b>{{ c[0] }}</b></RouterLink>
      </nav>
      <div v-if="loading" class="home-skeleton" aria-label="正在加载"><i v-for="n in 3" :key="n"></i></div>
      <div v-else-if="error" class="home-error" role="status"><ShieldAlert /><div><b>内容暂时没有加载成功</b><p>{{ error }}</p></div><button type="button" @click="$router.go(0)">重新加载</button></div>
      <template v-else>
        <section v-if="items[0]" class="today-focus">
          <div class="section-kicker">今日重点</div>
          <div><span>{{ items[0].category }}</span><h2>{{ items[0].title }}</h2><p>{{ items[0].summary }}</p><RouterLink :to="`/${news.includes(items[0]) ? 'news' : 'guide'}/${items[0].slug}`">查看重点内容<ArrowRight /></RouterLink></div>
        </section>
        <section class="guide-focus">
          <header><div><span class="section-icon"><Wrench /></span><div><h2>办事指南</h2><p>适用条件、材料、地点和步骤，一次说清楚</p></div></div><RouterLink to="/category/生活服务">更多<ChevronRight /></RouterLink></header>
          <div v-if="guides.length" class="guide-grid"><ContentCard v-for="item in guides.slice(0, 3)" :key="item.id" :item="item" kind="guide" /></div>
          <div v-else class="compact-empty">暂时没有可展示的办事指南</div>
        </section>
        <section class="latest">
          <header><div><h2>权威资讯</h2><p>来自政府、医院和社区的可靠信息</p></div><RouterLink to="/category/全部">查看全部</RouterLink></header>
          <ContentCard v-for="item in (news.length ? news : items).slice(0, 4)" :key="item.id" :item="item" kind="news" />
          <div v-if="!items.length" class="compact-empty">当前没有已发布内容</div>
        </section>
      </template>
      <div class="listen-tip"><Volume2 /><span><b>看字累了？</b>详情页可朗读、暂停和停止</span></div>
    </main>
    <BottomNav />
  </div>
</template>