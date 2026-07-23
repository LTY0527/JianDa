<script setup lang="ts">
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { MessageCircleQuestion } from "lucide-vue-next";
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main app-section-page"><header class="app-section-head"><MessageCircleQuestion /><div><h1>简达助手</h1><p>回答来自平台已经人工审核并发布的内容。</p></div></header><div class="app-section-pending">请选择推荐问题，或输入您想了解的内容。</div></main><BottomNav /></div></template>