<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { fetchDetail, publicOriginalFileUrl } from "../api";
import PdfReader from "@jianda/shared-ui/PdfReader.vue";
import ImageReader from "@jianda/shared-ui/ImageReader.vue";

const route = useRoute();
const item = ref<any>(null);
const error = ref("");
const fileUrl = computed(() => publicOriginalFileUrl(String(route.params.slug)));
const downloadUrl = computed(() => publicOriginalFileUrl(String(route.params.slug), true));
const isImage = computed(() => String(item.value?.mime_type || "").startsWith("image/"));

onMounted(async () => {
  try {
    item.value = await fetchDetail(String(route.params.slug));
    if (item.value.source_type === "WEB_ARTICLE") {
      error.value = "网页文章没有 PDF 或图片原文件，请查看官方原文。";
    } else if (!item.value.original_file_available) {
      error.value = "发布机构未公开原文件，或原文件已不可用。";
    }
  } catch {
    error.value = "原文件暂时无法读取。";
  }
});
</script>

<template>
  <div class="original-file-page">
    <AppTopBar :title="isImage ? '查看原图' : '查看原PDF'" />
    <main>
      <p v-if="error" class="withdrawn-state">{{ error }}</p>
      <ImageReader
        v-else-if="item && isImage"
        :src="fileUrl"
        :download-url="downloadUrl"
        :filename="item.original_filename || item.title"
        :alt="item.title"
      />
      <PdfReader
        v-else-if="item"
        :src="fileUrl"
        :download-url="downloadUrl"
        :filename="item.original_filename || `${item.title}.pdf`"
      />
      <p v-else>正在读取原文件…</p>
    </main>
  </div>
</template>
