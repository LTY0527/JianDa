<script setup lang="ts">
import { ref } from "vue";

const props = withDefaults(
  defineProps<{
    src: string;
    downloadUrl?: string;
    filename?: string;
    alt?: string;
    headers?: Record<string, string>;
  }>(),
  { downloadUrl: "", filename: "原图", alt: "材料原图", headers: () => ({}) },
);
const scale = ref(1);
const error = ref("");

async function download() {
  try {
    const response = await fetch(props.downloadUrl || `${props.src}?download=true`, {
      headers: props.headers,
    });
    if (!response.ok) throw new Error(String(response.status));
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = props.filename;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch {
    error.value = "下载失败，请稍后重试。";
  }
}
</script>

<template>
  <section class="image-reader">
    <div class="image-reader__toolbar">
      <button type="button" @click="scale = Math.max(0.5, scale - 0.2)">缩小</button>
      <span>{{ Math.round(scale * 100) }}%</span>
      <button type="button" @click="scale = Math.min(3, scale + 0.2)">放大</button>
      <button type="button" @click="scale = 1">适合窗口</button>
      <button type="button" @click="download">下载原图</button>
    </div>
    <p v-if="error" class="image-reader__error">{{ error }}</p>
    <div class="image-reader__viewport">
      <img :src="src" :alt="alt" :style="{ transform: `scale(${scale})` }" />
    </div>
  </section>
</template>

<style scoped>
.image-reader {
  overflow: hidden;
  background: #eef1f4;
  border: 1px solid #d7dde4;
  border-radius: 10px;
}
.image-reader__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem;
  background: #fff;
}
.image-reader button {
  min-height: 40px;
  padding: 0.45rem 0.75rem;
  color: #17324d;
  background: #fff;
  border: 1px solid #aebac7;
  border-radius: 6px;
}
.image-reader__viewport {
  overflow: auto;
  display: grid;
  place-items: center;
  min-height: 24rem;
  max-height: 72vh;
  padding: 1rem;
}
.image-reader img {
  max-width: 100%;
  max-height: 68vh;
  object-fit: contain;
  transform-origin: center;
  transition: transform 120ms ease;
}
.image-reader__error {
  color: #9d2f2f;
  text-align: center;
}
</style>
