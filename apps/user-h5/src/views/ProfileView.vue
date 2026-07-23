<script setup lang="ts">
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { UserRound } from "lucide-vue-next";
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main app-section-page"><header class="app-section-head"><UserRound /><div><h1>我的</h1><p>游客使用中，收藏、历史和偏好仅保存在本机。</p></div></header><div class="app-section-pending">管理收藏、浏览历史和阅读设置。</div></main><BottomNav /></div></template>