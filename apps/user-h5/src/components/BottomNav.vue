<script setup lang="ts">
import { Home, UsersRound, MessageCircleQuestion, ClipboardList, UserRound } from "lucide-vue-next";
import { useRoute } from "vue-router";
const route = useRoute();
const links = [
  { to: "/", icon: Home, label: "首页", matches: ["/"] },
  { to: "/neighborhood", icon: UsersRound, label: "邻里", matches: ["/neighborhood"] },
  { to: "/assistant", icon: MessageCircleQuestion, label: "简达助手", primary: true, matches: ["/assistant"] },
  { to: "/services", icon: ClipboardList, label: "服务", matches: ["/services", "/activities", "/guide", "/steps"] },
  { to: "/profile", icon: UserRound, label: "我的", matches: ["/profile", "/favorites", "/history", "/reminders", "/settings"] },
];
function isActive(matches: string[]) {
  return matches.some((path) => path === "/" ? route.path === "/" : route.path.startsWith(path));
}
</script>
<template>
  <nav class="bottom-nav" aria-label="主要导航">
    <RouterLink v-for="link in links" :key="link.to" :to="link.to" :class="{ active: isActive(link.matches), 'bottom-nav__primary': link.primary }" :aria-label="link.label">
      <span class="bottom-nav__icon"><component :is="link.icon" /></span><span>{{ link.label }}</span>
    </RouterLink>
  </nav>
</template>
