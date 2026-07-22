<script setup lang="ts">
import { Home, Grid2X2, Heart, Settings } from "lucide-vue-next";
import { useRoute } from "vue-router";
const route = useRoute();
const links = [
  ["/", Home, "首页"],
  ["/category/全部", Grid2X2, "分类"],
  ["/favorites", Heart, "收藏"],
  ["/settings", Settings, "设置"],
] as const;
</script>
<template>
  <nav class="bottom-nav" aria-label="底部导航">
    <RouterLink
      v-for="l in links"
      :to="l[0]"
      :class="{ active: route.path === l[0] }"
      ><component :is="l[1]" /><span>{{ l[2] }}</span></RouterLink
    >
  </nav>
</template>
