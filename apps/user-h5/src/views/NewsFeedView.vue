<script setup lang="ts">
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { Newspaper } from "lucide-vue-next";
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main app-section-page"><header class="app-section-head"><Newspaper /><div><h1>权威资讯</h1><p>持续浏览经过审核的政策、健康和生活信息。</p></div></header><div class="app-section-pending">资讯频道正在加载已发布内容…</div></main><BottomNav /></div></template>