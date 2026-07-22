<script setup lang="ts">
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { items } from "../data";
import { Heart } from "lucide-vue-next";
const saved = localStorage.getItem("favorite_1") === "1";
</script>
<template>
  <div class="h5-page">
    <H5Header />
    <main class="h5-main list-page">
      <div class="simple-head">
        <h1>我的收藏</h1>
        <p>把重要信息放在这里，随时回来查看。</p>
      </div>
      <section class="list-surface" v-if="saved">
        <ContentCard :item="items[0]" />
      </section>
      <div v-else class="empty">
        <Heart /><b>还没有收藏内容</b>
        <p>在详情页点击“收藏”，重要信息就不会找不到。</p>
        <RouterLink to="/" class="green-link">去首页看看</RouterLink>
      </div>
    </main>
    <BottomNav />
  </div>
</template>
