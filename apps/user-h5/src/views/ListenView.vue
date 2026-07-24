<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Headphones,
  Play,
  Pause,
  Square,
  SkipBack,
  SkipForward,
  Heart,
  History,
  Radio,
  WifiOff,
} from "lucide-vue-next";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import SpeechRateSelector from "../components/SpeechRateSelector.vue";
import { fetchItems, type PublicItem } from "../api";
import { contentKind, importanceScore } from "../content";
import {
  favoriteItems,
  listenHistoryItems,
  recordListen,
} from "../library";
import { useSpeechPlayer } from "../composables/useSpeechPlayer";

type ListenTab = "today" | "政策" | "健康" | "反诈" | "养老" | "favorites" | "recent";

const route = useRoute();
const router = useRouter();
const items = ref<PublicItem[]>([]);
const loading = ref(true);
const loadError = ref("");
const activeId = ref<number | null>(null);
const initialTab = String(route.query.tab || "today");
const tab = ref<ListenTab>(
  ["today", "政策", "健康", "反诈", "养老", "favorites", "recent"].includes(initialTab)
    ? (initialTab as ListenTab)
    : "today",
);

const sections = [
  { id: "today", label: "今日早报", icon: Radio },
  { id: "政策", label: "政策播报", icon: Radio },
  { id: "健康", label: "健康提醒", icon: Radio },
  { id: "反诈", label: "反诈提醒", icon: Radio },
  { id: "养老", label: "养老服务", icon: Radio },
  { id: "favorites", label: "我的收藏", icon: Heart },
  { id: "recent", label: "最近听过", icon: History },
] as const;

const availableIds = computed(() => new Set(items.value.map((item) => item.id)));
const queue = computed(() => {
  if (tab.value === "favorites") {
    return favoriteItems().filter((item) => availableIds.value.has(item.id));
  }
  if (tab.value === "recent") {
    return listenHistoryItems().filter((item) => availableIds.value.has(item.id));
  }
  if (tab.value === "today") {
    return [...items.value].sort((a, b) => importanceScore(b) - importanceScore(a)).slice(0, 8);
  }
  const category = tab.value === "政策" ? "时政" : tab.value;
  return items.value.filter((item) => item.category === category);
});
const currentIndex = computed(() => queue.value.findIndex((item) => item.id === activeId.value));
const current = computed(() => queue.value[currentIndex.value] || queue.value[0] || null);
const speechText = computed(() =>
  current.value ? `${current.value.title}。${current.value.summary}。来源：${current.value.source_name}。` : "",
);

function finishCurrent() {
  if (currentIndex.value >= 0 && currentIndex.value < queue.value.length - 1) {
    activeId.value = queue.value[currentIndex.value + 1].id;
    playCurrent();
  }
}
const speech = useSpeechPlayer(finishCurrent);

function playCurrent(item?: PublicItem) {
  const target = item || current.value;
  if (!target) return;
  activeId.value = target.id;
  recordListen(target);
  speech.play(`${target.title}。${target.summary}。来源：${target.source_name}。`);
}
function selectTab(next: ListenTab) {
  speech.stop();
  tab.value = next;
  activeId.value = null;
  void router.replace({ path: "/listen", query: next === "today" ? {} : { tab: next } });
}
function move(offset: number) {
  if (!queue.value.length) return;
  const base = currentIndex.value < 0 ? 0 : currentIndex.value;
  const next = Math.min(queue.value.length - 1, Math.max(0, base + offset));
  playCurrent(queue.value[next]);
}
async function load() {
  loading.value = true;
  loadError.value = "";
  try {
    items.value = await fetchItems();
  } catch {
    loadError.value = "暂时无法读取已发布内容，请检查网络后重试。";
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <div class="h5-page">
    <H5Header />
    <main class="h5-main listen-page">
      <header class="listen-hero">
        <span><Headphones /></span>
        <div>
          <h1>听一听</h1>
          <p>使用浏览器自带语音朗读已审核内容，无需下载音频。</p>
        </div>
      </header>

      <div v-if="!speech.supported.value" class="listen-warning" role="status">
        当前浏览器不支持语音播报，您仍可打开内容使用大字阅读。
      </div>
      <div v-if="loadError" class="home-error" role="status">
        <WifiOff /><div><b>播放内容加载失败</b><p>{{ loadError }}</p></div>
        <button type="button" @click="load">重新加载</button>
      </div>

      <section class="listen-player" aria-label="语音播放器">
        <div class="listen-now">
          <small>正在准备播放</small>
          <h2>{{ current?.title || (loading ? "正在加载已审核内容…" : "当前队列暂无内容") }}</h2>
          <p v-if="current">{{ current.source_name }} · {{ current.category }}</p>
        </div>
        <div class="listen-controls">
          <button type="button" aria-label="上一条" :disabled="currentIndex <= 0" @click="move(-1)"><SkipBack /></button>
          <button
            class="listen-play"
            type="button"
            :disabled="!current"
            :aria-label="speech.status.value === 'playing' ? '暂停' : speech.status.value === 'paused' ? '继续' : '播放'"
            @click="speech.status.value === 'playing' ? speech.pause() : speech.status.value === 'paused' ? speech.resume() : playCurrent()"
          >
            <Pause v-if="speech.status.value === 'playing'" />
            <Play v-else />
          </button>
          <button type="button" aria-label="停止" :disabled="!speech.isActive.value" @click="speech.stop"><Square /></button>
          <button type="button" aria-label="下一条" :disabled="currentIndex >= queue.length - 1" @click="move(1)"><SkipForward /></button>
        </div>
        <SpeechRateSelector :model-value="speech.rate.value" @select="speech.setRate" />
        <p v-if="speech.error.value" class="listen-error">{{ speech.error.value }}</p>
      </section>

      <nav class="listen-channels" aria-label="收听频道">
        <button
          v-for="section in sections"
          :key="section.id"
          type="button"
          :class="{ active: tab === section.id }"
          @click="selectTab(section.id)"
        >
          <component :is="section.icon" />{{ section.label }}
        </button>
      </nav>

      <section class="listen-queue">
        <header>
          <div><h2>{{ sections.find((section) => section.id === tab)?.label }}</h2><p>共 {{ queue.length }} 条已发布内容</p></div>
          <button v-if="queue.length" type="button" @click="playCurrent(queue[0])"><Play />一键播放</button>
        </header>
        <div v-if="loading" class="list-skeleton"><i v-for="n in 4" :key="n"></i></div>
        <button
          v-for="(item, index) in queue"
          v-else
          :key="item.id"
          type="button"
          class="listen-queue-item"
          :class="{ active: current?.id === item.id }"
          @click="playCurrent(item)"
        >
          <span>{{ String(index + 1).padStart(2, "0") }}</span>
          <div><b>{{ item.title }}</b><small>{{ item.source_name }} · {{ item.category }}</small></div>
          <Play />
        </button>
        <div v-if="!loading && !queue.length" class="compact-empty">
          {{ tab === "favorites" ? "还没有收藏内容" : tab === "recent" ? "还没有收听记录" : "当前频道暂无已发布内容" }}
        </div>
      </section>

      <RouterLink v-if="current" class="listen-detail-link" :to="`/${contentKind(current)}/${current.slug}`">
        查看当前内容详情
      </RouterLink>
    </main>
    <BottomNav />
  </div>
</template>
