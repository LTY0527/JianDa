<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import H5Header from "../components/H5Header.vue";
import { fetchDetail, setFavorite } from "../api";
import {
  ArrowLeft,
  ShieldCheck,
  Volume2,
  Pause,
  Square,
  Type,
  Heart,
  FileText,
  MapPin,
  CalendarClock,
  Phone,
  ChevronRight,
  CheckCircle2,
  TriangleAlert,
} from "lucide-vue-next";
const route = useRoute();
const font = ref(Number(localStorage.getItem("jianda_font") || 18));
const favorite = ref(false);
const speaking = ref(false);
const error = ref("");
const item = ref<any>({
  id: 0,
  slug: String(route.params.slug),
  title: "正在加载…",
  category: "",
  source_name: "",
  published_at: "",
});
const detail = ref<{
  summary: string[];
  materials: string[];
  steps: string[][];
}>({ summary: [], materials: [], steps: [] });
onMounted(async () => {
  try {
    item.value = await fetchDetail(String(route.params.slug));
    const generated = item.value.generated || {};
    const fields = item.value.fields || [];
    detail.value.summary = Array.isArray(generated.SUMMARY)
      ? generated.SUMMARY
      : [];
    detail.value.steps = Array.isArray(generated.STEP_CARDS)
      ? generated.STEP_CARDS.map((step: any) => [step.title, step.description])
      : [];
    const material = fields.find(
      (field: any) => field.field_type === "MATERIAL",
    )?.field_value;
    detail.value.materials = material
      ? String(material)
          .split(/[、，,]/)
          .filter(Boolean)
      : [];
    favorite.value = localStorage.getItem(`favorite_${item.value.id}`) === "1";
  } catch {
    error.value = "内容暂时无法读取，可能已撤回";
  }
});
async function toggleFav() {
  const next = !favorite.value;
  try {
    await setFavorite(item.value.id, next);
    favorite.value = next;
    localStorage.setItem(`favorite_${item.value.id}`, next ? "1" : "0");
  } catch {
    error.value = "收藏操作失败，请稍后重试";
  }
}
function speak() {
  if (!("speechSynthesis" in window)) return;
  if (speaking.value) {
    speechSynthesis.pause();
    speaking.value = false;
    return;
  }
  const utterance = new SpeechSynthesisUtterance(
    detail.value.summary.join("。") +
      detail.value.steps.map((step) => step.join("。")).join("。"),
  );
  utterance.lang = "zh-CN";
  utterance.rate = Number(localStorage.getItem("jianda_rate") || 0.9);
  utterance.onend = () => (speaking.value = false);
  speechSynthesis.cancel();
  speechSynthesis.speak(utterance);
  speaking.value = true;
}
function stop() {
  speechSynthesis?.cancel();
  speaking.value = false;
}
function grow() {
  font.value = font.value >= 24 ? 18 : font.value + 2;
  localStorage.setItem("jianda_font", String(font.value));
}
onBeforeUnmount(stop);
</script>
<template>
  <div class="detail-page" :style="{ '--reader-size': font + 'px' }">
    <H5Header />
    <main class="reader">
      <button class="back" @click="$router.back()"><ArrowLeft />返回</button>
      <article class="article-head">
        <span class="category-text">{{ item.category }} · 办事指南</span>
        <h1>{{ item.title }}</h1>
        <div class="source">
          <ShieldCheck /><span
            ><b>{{ item.source_name }}</b
            ><small
              >权威来源 ·
              {{ String(item.published_at).slice(0, 10) }} 发布</small
            ></span
          >
        </div>
      </article>
      <nav class="reader-tools">
        <button @click="speak" :class="{ active: speaking }">
          <component :is="speaking ? Pause : Volume2" /><span>{{
            speaking ? "暂停" : "听全文"
          }}</span></button
        ><button v-if="speaking" @click="stop">
          <Square /><span>停止</span></button
        ><button @click="grow">
          <Type /><span>{{ font }}px</span></button
        ><button @click="toggleFav" :class="{ active: favorite }">
          <Heart :fill="favorite ? 'currentColor' : 'none'" /><span>{{
            favorite ? "已收藏" : "收藏"
          }}</span></button
        ><RouterLink :to="`/original/${item.slug}`"
          ><FileText /><span>看原文</span></RouterLink
        >
      </nav>
      <p v-if="error" class="warm-tip">{{ error }}</p>
      <section class="summary-block">
        <h2>三句话看懂</h2>
        <ol>
          <li v-for="(s, i) in detail.summary">
            <span>{{ i + 1 }}</span>
            <p>{{ s }}</p>
          </li>
        </ol>
      </section>
      <section class="reader-section">
        <h2>我是否符合条件？</h2>
        <div class="answer yes">
          <CheckCircle2 />
          <p>
            <b>符合以下条件即可申请</b>具有本市户籍，并且已经年满 80
            周岁。目前没有领取同类生活补贴。
          </p>
        </div>
      </section>
      <section class="reader-section">
        <h2>需要准备什么？</h2>
        <ul class="material-list">
          <li v-for="m in detail.materials"><CheckCircle2 />{{ m }}</li>
        </ul>
        <p class="warm-tip">
          <TriangleAlert />建议出门前把原件和复印件分别装好，避免遗漏。
        </p>
      </section>
      <section class="quick-info">
        <article>
          <CalendarClock /><span
            ><small>办理时间</small><b>工作日 9:00—17:00</b></span
          >
        </article>
        <article>
          <MapPin /><span
            ><small>办理地点</small><b>户籍所在地社区服务窗口</b></span
          >
        </article>
        <article>
          <Phone /><span><small>咨询电话</small><b>021-12345</b></span>
        </article>
      </section>
      <section class="reader-section step-section">
        <h2>办理步骤</h2>
        <ol>
          <li v-for="(s, i) in detail.steps">
            <span>{{ i + 1 }}</span>
            <div>
              <h3>{{ s[0] }}</h3>
              <p>{{ s[1] }}</p>
            </div>
          </li>
        </ol>
      </section>
      <section class="reader-section">
        <h2>专业术语解释</h2>
        <dl>
          <dt>同类生活补贴</dt>
          <dd>
            指用途和对象相近、不能重复领取的政府补助。拿不准时，可以带上已有补贴凭证到窗口询问。
          </dd>
        </dl>
      </section>
      <RouterLink class="original-link" :to="`/original/${item.slug}`"
        ><FileText /><span
          ><b>查看原始通知</b><small>共 3 页，可核对原文内容</small></span
        ><ChevronRight
      /></RouterLink>
      <p class="disclaimer">
        内容由简达整理并经人工审核，具体办理要求以服务窗口最新规定为准。
      </p>
    </main>
  </div>
</template>
