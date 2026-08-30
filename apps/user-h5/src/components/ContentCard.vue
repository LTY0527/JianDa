<script setup lang="ts">
import { computed, ref } from "vue";
import { ShieldCheck, ChevronRight, ExternalLink, Heart, MapPin, Volume2 } from "lucide-vue-next";
import { setFavorite } from "../api";
import { normalizeTitle, truncateSummary, contentKind, isFavorite, isRead } from "../content";
import { saveFavorite } from "../library";
import { articleCover, categoryDefaultCover } from "../utils/coverImage";
import { SPEECH_RATES } from "../composables/useSpeechPlayer";
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
function fallbackCover(event: Event) {
  const image = event.currentTarget as HTMLImageElement;
  const attempt = Number(image.dataset.fallbackAttempt || "0") + 1;
  image.dataset.fallbackAttempt = String(attempt);
  const fallback = categoryDefaultCover(props.item, attempt);
  if (!image.src.endsWith(fallback)) image.src = fallback;
}
function listen() {
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(`${props.item.title}。${props.item.summary}`);
  utterance.lang = "zh-CN";
  const savedRate = Number(localStorage.getItem("jianda_rate") || 1);
  utterance.rate = SPEECH_RATES.includes(savedRate as (typeof SPEECH_RATES)[number]) ? savedRate : 1;
  window.speechSynthesis.speak(utterance);
}
</script>
<template>
  <article class="content-row editorial-card" :class="{ 'content-row--read': read }">
    <RouterLink class="editorial-card__image" :to="`/${kind}/${item.slug}`">
      <img :src="articleCover(item)" :alt="item.image_alt_text || `${item.title}配图`" loading="lazy" decoding="async" referrerpolicy="no-referrer" @error="fallbackCover" />
    </RouterLink>
    <RouterLink class="content-row__body" :to="`/${kind}/${item.slug}`">
      <span class="category-text">{{ item.category }} · {{ kind === "news" ? "权威资讯" : "办事指南" }} <template v-if="item.is_local">· <MapPin/>本地</template></span>
      <h3>{{ normalizeTitle(item.title) }}</h3>
      <p>{{ truncateSummary(item.summary) }}</p>
      <footer><ShieldCheck />{{ item.source || item.source_name }}<span>· {{ String(item.date || item.published_at).slice(0, 10) }}</span><span>· {{ item.reading_minutes || 1 }}分钟</span><span v-if="read">· 已读</span></footer>
    </RouterLink>
    <div v-if="actions" class="content-row__actions">
      <RouterLink :to="`/${kind}/${item.slug}`">查看适老版</RouterLink>
      <button type="button" aria-label="朗读摘要" @click="listen"><Volume2 />听一听</button>
      <button type="button" :aria-label="favorite ? '取消收藏' : '收藏'" @click="toggleFavorite"><Heart :fill="favorite ? 'currentColor' : 'none'" /></button>
      <a v-if="item.source_url" :href="item.source_url" target="_blank" rel="noopener noreferrer" aria-label="查看官方原文"><ExternalLink/>官方原文</a>
    </div>
    <ChevronRight class="row-arrow" />
  </article>
</template>
