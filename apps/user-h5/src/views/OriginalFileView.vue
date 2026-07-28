<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { fetchDetail, publicOriginalFileUrl } from "../api";

const route = useRoute();
const item = ref<any>(null);
const error = ref("");
const fileUrl = computed(() => publicOriginalFileUrl(String(route.params.slug)));
const isImage = computed(() => String(item.value?.mime_type || "").startsWith("image/"));

onMounted(async () => {
  try {
    item.value = await fetchDetail(String(route.params.slug));
    if (!item.value.original_file_available) error.value = "发布机构未公开原文件。";
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
      <img v-else-if="item && isImage" :src="fileUrl" :alt="item.title" />
      <iframe v-else-if="item" :src="fileUrl" :title="item.title"></iframe>
      <p v-else>正在读取原文件…</p>
    </main>
  </div>
</template>
