<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  LayoutDashboard,
  Files,
  ScrollText,
  ChevronDown,
  HeartHandshake,
  RadioTower,
  Activity,
  BadgeCheck,
} from "lucide-vue-next";
import { currentUser } from "../auth";
const route = useRoute();
const router = useRouter();
const user = currentUser();
const menus = computed(() => [
  { path: "/", label: "工作台", icon: LayoutDashboard },
  { path: "/documents", label: "内容中心", icon: Files },
  ...(user?.role === "PLATFORM_ADMIN"
    ? [
        { path: "/public-sources", label: "采集与来源", icon: RadioTower },
        { path: "/operations", label: "数据概览", icon: Activity },
        { path: "/commercial", label: "商业运营", icon: BadgeCheck },
      ]
    : []),
  { path: "/logs", label: "系统记录", icon: ScrollText },
]);
function isActive(path: string) {
  if (path === "/") return route.path === "/";
  if (path === "/documents") {
    return route.path.startsWith("/documents") || route.path === "/published" || route.path === "/public-import";
  }
  return route.path === path;
}
function logout() {
  localStorage.removeItem("jianda_token");
  localStorage.removeItem("jianda_user_info");
  localStorage.removeItem("jianda_user");
  router.push("/login");
}
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark"><HeartHandshake :size="22" /></span
        ><span><b>简达</b><small>适老化信息平台</small></span>
      </div>
      <nav aria-label="主导航">
        <RouterLink
          v-for="m in menus"
          :key="m.path"
          :to="m.path"
          :class="{
            active: isActive(m.path),
          }"
          ><component :is="m.icon" :size="19" /><span>{{
            m.label
          }}</span></RouterLink
        >
      </nav>
      <div class="side-help">
        <b>需要帮助？</b><span>查看操作手册或联系平台服务人员</span
        ><button>查看帮助中心</button>
      </div>
    </aside>
    <div class="main-area">
      <header class="topbar">
        <div>
          <span class="org-label">当前机构</span
          ><strong>{{ user?.organizationName || "当前机构" }}</strong>
        </div>
        <button class="account" @click="logout">
          <span class="avatar">{{ user?.displayName?.slice(0, 1) || "简" }}</span
          ><span><b>{{ user?.displayName || "当前用户" }}</b><small>{{ user?.role === "PLATFORM_ADMIN" ? "平台管理员" : user?.role === "REVIEWER" ? "审核员" : "机构管理员" }}</small></span
          ><ChevronDown :size="16" />
        </button>
      </header>
      <main><RouterView /></main>
    </div>
  </div>
</template>
