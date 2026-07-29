<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  LayoutDashboard,
  Files,
  FileCheck2,
  CloudDownload,
  ScrollText,
  ChevronDown,
  HeartHandshake,
  ShieldCheck,
  Activity,
} from "lucide-vue-next";
import { currentUser } from "../auth";
const route = useRoute();
const router = useRouter();
const user = currentUser();
const menus = computed(() => [
  { path: "/", label: "工作台", icon: LayoutDashboard },
  { path: "/documents", label: "材料管理", icon: Files },
  { path: "/published", label: "已发布内容", icon: FileCheck2 },
  ...(user?.role === "PLATFORM_ADMIN"
    ? [
        { path: "/public-sources", label: "权威来源管理", icon: ShieldCheck },
        { path: "/public-import", label: "公开信息导入", icon: CloudDownload },
        { path: "/operations", label: "平台运营看板", icon: Activity },
      ]
    : []),
  { path: "/logs", label: "操作日志", icon: ScrollText },
]);
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
            active:
              route.path === m.path ||
              (m.path === '/documents' && route.path.startsWith('/documents')),
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
