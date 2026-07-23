<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { fetchDetail, type PublicItem } from "../api";
import { contentKind } from "../content";
import { Download } from "lucide-vue-next";
const route = useRoute();
const title = ref("查看原文");
const rawText = ref("");
const source = ref("");
const error = ref("");
const downloadStatus = ref("");
function downloadOriginal() {
  downloadStatus.value = "";
  if (!rawText.value) {
    downloadStatus.value = "原文尚未提供，暂时无法下载。";
    return;
  }
  try {
    const safeTitle =
      title.value.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_").replace(/[. ]+$/g, "") ||
      "简达原文";
    const content = `${title.value}\n原文来源：${source.value || "未注明"}\n\n${rawText.value}`;
    const url = URL.createObjectURL(
      new Blob(["\ufeff", content], { type: "text/plain;charset=utf-8" }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${safeTitle}.txt`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
    downloadStatus.value = "原文已开始下载，请在浏览器下载记录中查看。";
  } catch {
    downloadStatus.value = "原文下载失败，请稍后重试。";
  }
}
onMounted(async () => {
  try {
    const item = await fetchDetail(String(route.params.slug));
    title.value = item.title;
    rawText.value = item.raw_text || "";
    source.value = item.source_name;
    route.meta.backTo = `/${contentKind(item as PublicItem)}/${item.slug}`;
    if (!rawText.value) error.value = "原文暂未录入，您可以返回详情查看通俗版内容。";
  } catch {
    error.value = "原文暂时无法读取";
  }
});
</script>
<template>
  <div class="detail-page">
    <AppTopBar title="查看原文" />
    <main class="reader original">
      <div class="original-heading">
        <div><span>原文来源</span><h1>查看原文</h1></div>
        <button type="button" aria-label="下载原文" :disabled="Boolean(error)" @click="downloadOriginal"><Download /></button>
      </div>
      <p v-if="downloadStatus" class="page-number" role="status">{{ downloadStatus }}</p>
      <article class="document">
        <h2>{{ title }}</h2>
        <p class="original-text">{{ rawText || "暂无可展示的原文内容。" }}</p>
        <footer>{{ source }}</footer>
      </article>
      <p v-if="error" class="page-number">{{ error }}</p>
      <p v-else class="page-number">原始材料内容 · 可与通俗版逐项核对</p>
    </main>
  </div>
</template>
