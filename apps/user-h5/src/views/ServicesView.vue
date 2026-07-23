<script setup lang="ts">
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { ClipboardList } from "lucide-vue-next";
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main app-section-page"><header class="app-section-head"><ClipboardList /><div><h1>办事专区</h1><p>按服务对象和事项类型找到清楚的办理方法。</p></div></header><div class="app-section-pending">正在整理可办理事项…</div></main><BottomNav /></div></template>