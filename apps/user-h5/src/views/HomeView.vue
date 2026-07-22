<script setup lang="ts">
import { onMounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems } from "../api";
import {
  Search,
  Landmark,
  HeartPulse,
  HandHeart,
  ShieldAlert,
  Wrench,
  Drama,
  ChevronRight,
  Volume2,
} from "lucide-vue-next";
const items = ref<any[]>([]);
const error = ref("");
const cats = [
  ["时政", Landmark],
  ["健康", HeartPulse],
  ["养老", HandHeart],
  ["反诈", ShieldAlert],
  ["生活服务", Wrench],
  ["文化", Drama],
] as const;
onMounted(async () => {
  try {
    items.value = await fetchItems();
  } catch {
    error.value = "暂时无法读取内容，请确认服务已启动";
  }
});
</script>
<template>
  <div class="h5-page">
    <H5Header />
    <main class="h5-main">
      <section class="welcome">
        <h1>办事不犯难，信息看得懂</h1>
        <p>权威公共服务信息，经过人工审核后为您清楚说明。</p>
        <RouterLink to="/search" class="search-box"
          ><Search />搜索办事指南、健康资讯</RouterLink
        >
      </section>
      <section class="categories">
        <RouterLink v-for="c in cats" :to="`/category/${c[0]}`"
          ><span><component :is="c[1]" /></span><b>{{ c[0] }}</b></RouterLink
        >
      </section>
      <section class="guide-focus">
        <header>
          <div>
            <span class="section-icon"><Wrench /></span>
            <div>
              <h2>办事指南专区</h2>
              <p>材料、时间、地点和步骤，一次说清楚</p>
            </div>
          </div>
          <RouterLink to="/category/办事指南">更多<ChevronRight /></RouterLink>
        </header>
        <div class="featured-guide">
          <span>常用指南</span>
          <h3>老年补贴申请指南</h3>
          <p>符合什么条件？需要带什么？去哪里办？</p>
          <div><b>5 个步骤</b><b>约 3 分钟读完</b></div>
          <RouterLink to="/guide/elderly-subsidy"
            >查看办理方法 <ChevronRight
          /></RouterLink>
        </div>
      </section>
      <section class="latest">
        <header>
          <div>
            <h2>最新权威信息</h2>
            <p>来自政府、医院和社区的可靠信息</p>
          </div>
          <RouterLink to="/category/全部">查看全部</RouterLink>
        </header>
        <ContentCard v-for="i in items.slice(0, 4)" :key="i.id" :item="i" />
        <p v-if="error" class="result-count">{{ error }}</p>
      </section>
      <div class="listen-tip">
        <Volume2 /><span><b>看字累了？</b>详情页可以为您朗读内容</span>
      </div>
    </main>
    <BottomNav />
  </div>
</template>
