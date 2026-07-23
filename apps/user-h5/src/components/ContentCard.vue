<script setup lang="ts">
import { computed, ref } from "vue";
import { ShieldCheck, ChevronRight, Heart, Volume2 } from "lucide-vue-next";
import { setFavorite } from "../api";
import { cleanDisplayTitle, contentKind, isFavorite, isRead } from "../content";
import { saveFavorite } from "../library";
const props = withDefaults(defineProps<{ item: any; kind?: "guide" | "news"; actions?: boolean }>(), { actions: false });
const kind = computed(() => props.kind || contentKind(props.item));
const favorite = ref(isFavorite(props.item.id));
const read = computed(() => isRead(props.item.id));
async function toggleFavorite() {
  const next = !favorite.value;
  await setFavorite(props.item.id, next);
  favorite.value = next;
  localStorage.setItem(`favorite_${props.item.id}`, next ? "1" : "0");
  saveFavorite(props.item, next);
}
</script>
<template>
  <article class="content-row" :class="{ 'content-row--read': read }">
    <RouterLink class="content-row__body" :to="`/${kind}/${item.slug}`">
      <span class="category-text">{{ item.category }} · {{ kind === "news" ? "权威资讯" : "办事指南" }}</span>
      <h3>{{ cleanDisplayTitle(item.title) }}</h3>
      <p>{{ item.summary }}</p>
      <footer><ShieldCheck />{{ item.source || item.source_name }}<span>· {{ String(item.date || item.published_at).slice(0, 10) }}</span><span v-if="read">· 已读</span></footer>
    </RouterLink>
    <div v-if="actions" class="content-row__actions">
      <span title="详情支持语音朗读"><Volume2 />可听</span>
      <button type="button" :aria-label="favorite ? '取消收藏' : '收藏'" @click="toggleFavorite"><Heart :fill="favorite ? 'currentColor' : 'none'" /></button>
    </div>
    <ChevronRight class="row-arrow" />
  </article>
</template>