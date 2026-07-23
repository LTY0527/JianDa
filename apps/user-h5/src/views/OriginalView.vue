<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { fetchDetail } from "../api";
import { Download } from "lucide-vue-next";
const route = useRoute();
const title = ref("原始通知");
const rawText = ref("");
const source = ref("");
const error = ref("");
onMounted(async () => {
  try {
    const item = await fetchDetail(String(route.params.slug));
    title.value = item.title;
    rawText.value = item.raw_text || "原文暂未录入";
    source.value = item.source_name;
  } catch {
    error.value = "原文暂时无法读取";
  }
});
</script>
<template>
  <div class="detail-page">
    <AppTopBar title="原始通知" />
    <main class="reader original">
      <div class="original-heading">
        <div><span>权威来源原文</span><h1>原始通知</h1></div>
        <button type="button" aria-label="下载原文"><Download /></button>
      </div>
      <article class="document">
        <h2>{{ title }}</h2>
        <p class="original-text">{{ rawText }}</p>
        <footer>{{ source }}</footer>
      </article>
      <p v-if="error" class="page-number">{{ error }}</p>
      <p v-else class="page-number">原始材料内容 · 可与通俗版逐项核对</p>
    </main>
  </div>
</template>
