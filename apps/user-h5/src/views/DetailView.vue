<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { fetchDetail, setFavorite } from "../api";
import { cleanDisplayTitle } from "../content";
import { readerPreferences, recordVisit, saveFavorite } from "../library";
import SpeechRateSelector from "../components/SpeechRateSelector.vue";
import { useSpeechPlayer } from "../composables/useSpeechPlayer";
import { buildTelephoneHref, copyText } from "../utils/contactActions";
import { deduplicateWarnings, sameDisplayText } from "../utils/contentNormalization";
import {
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
  MessageCircleQuestion,
} from "lucide-vue-next";
const route = useRoute();
const font = ref(Number(localStorage.getItem("jianda_font") || 18));
const favorite = ref(false);
const loading = ref(true);
const speech = useSpeechPlayer();
const isNews = computed(() => route.path.startsWith("/news/"));
const error = ref("");
const copyFeedback = ref("");
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
  warnings: string[];
  terms: Record<string, string>;
  fields: Record<string, string>;
  sessions: Array<{ date: string; time: string; location: string; needs_human_review?: boolean }>;
}>({ summary: [], materials: [], steps: [], warnings: [], terms: {}, fields: {}, sessions: [] });
const targetAudience = computed(() => detail.value.fields.TARGET_AUDIENCE || "");
const eligibility = computed(() => {
  const value = detail.value.fields.ELIGIBILITY || "";
  return value && sameDisplayText(value, targetAudience.value) ? "" : value;
});
const startDate = computed(() => detail.value.fields.START_DATE || "");
const endDate = computed(() => detail.value.fields.END_DATE || "");
const location = computed(() => detail.value.fields.LOCATION || "");
const contact = computed(() => detail.value.fields.CONTACT || "");
const fee = computed(() => detail.value.fields.FEE || "");
const fieldWarning = computed(() => detail.value.fields.WARNING || "");
const contactHref = computed(() => buildTelephoneHref(contact.value));
const registrationDate = computed(() => {
  if (startDate.value && endDate.value)
    return `${startDate.value} 至 ${endDate.value}`;
  return startDate.value || endDate.value;
});
const speechText = computed(() =>
  [
    cleanDisplayTitle(item.value.title),
    ...detail.value.summary,
    ...detail.value.steps.flatMap((step) => step),
  ].join("。"),
);
onMounted(async () => {
  try {
    item.value = await fetchDetail(String(route.params.slug));
    const generated = item.value.generated || {};
    const fields = item.value.fields || [];
    detail.value.fields = Object.fromEntries(
      fields
        .filter(
          (field: any) =>
            typeof field?.field_type === "string" &&
            String(field?.field_value || "").trim(),
        )
        .map((field: any) => [
          String(field.field_type),
          String(field.field_value).trim(),
        ]),
    );
    const generatedSummary = Array.isArray(generated.SUMMARY)
      ? generated.SUMMARY
      : [];
    detail.value.summary = generatedSummary.length
      ? generatedSummary
      : item.value.summary
        ? [item.value.summary]
        : [];
    detail.value.sessions = Array.isArray(generated.SESSIONS)
      ? generated.SESSIONS.filter(
          (session: any) =>
            session &&
            typeof session.date === "string" &&
            typeof session.time === "string" &&
            typeof session.location === "string",
        )
      : [];
    if (detail.value.sessions.length) {
      const schedule = detail.value.sessions
        .map((session) => `${session.date}接种时间为${session.time}`)
        .join("；");
      detail.value.summary = [
        detail.value.summary[0],
        `${schedule}。`,
        detail.value.summary[2],
      ].filter(Boolean);
    }
    detail.value.steps = detail.value.sessions.length
      ? detail.value.sessions.map((session) => [
          `${session.date}场次`,
          `${session.date} ${session.time}，地点：${session.location}。`,
        ])
      : Array.isArray(generated.STEP_CARDS)
      ? generated.STEP_CARDS.map((step: any) => [step.title, step.description])
      : [];
    detail.value.warnings = deduplicateWarnings([
      ...(Array.isArray(generated.RISK_WARNING) ? generated.RISK_WARNING : []),
      fieldWarning.value,
    ]);
    detail.value.terms =
      generated.TERM_EXPLANATION &&
      typeof generated.TERM_EXPLANATION === "object"
        ? generated.TERM_EXPLANATION
        : {};
    if (detail.value.terms["预防接种门诊"]) {
      detail.value.terms["预防接种门诊"] =
        "社区卫生服务机构中负责疫苗登记、接种前询问、健康检查和疫苗接种的服务区域。";
    }
    const material = fields.find(
      (field: any) => field.field_type === "MATERIAL",
    )?.field_value;
    detail.value.materials = material
      ? String(material)
          .split(/[、，,]/)
          .filter(Boolean)
      : [];
    favorite.value = localStorage.getItem(`favorite_${item.value.id}`) === "1";
    recordVisit(item.value as any);
    if (readerPreferences().autoRead) {
      window.setTimeout(() => speech.play(speechText.value), 250);
    }
  } catch {
    error.value = "内容暂时无法读取，可能已撤回";
  } finally {
    loading.value = false;
  }
});
async function toggleFav() {
  const next = !favorite.value;
  try {
    await setFavorite(item.value.id, next);
    favorite.value = next;
    localStorage.setItem(`favorite_${item.value.id}`, next ? "1" : "0");
    saveFavorite(item.value as any, next);
  } catch {
    error.value = "收藏操作失败，请稍后重试";
  }
}
async function copyAddress() {
  if (!location.value) return;
  copyFeedback.value = "";
  copyFeedback.value = (await copyText(location.value))
    ? "地址已复制"
    : "复制失败，请长按地址手动复制";
}
function grow() {
  font.value = font.value >= 24 ? 18 : font.value + 2;
  localStorage.setItem("jianda_font", String(font.value));
}
</script>
<template>
  <div class="detail-page" :style="{ '--reader-size': font + 'px' }">
    <AppTopBar />
    <main class="reader">

      <article class="article-head">
        <span class="category-text">{{ item.category }} · {{ isNews ? "权威资讯" : "办事指南" }}</span>
        <h1>{{ cleanDisplayTitle(item.title) }}</h1>
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
        <button @click="speech.toggle(speechText)" :class="{ active: speech.isActive.value }">
          <component :is="speech.status.value === 'playing' ? Pause : Volume2" /><span>{{
            speech.status.value === "playing" ? "暂停" : speech.status.value === "paused" ? "继续" : "听全文"
          }}</span></button
        ><button v-if="speech.isActive.value" @click="speech.stop">
          <Square /><span>停止</span></button
        ><SpeechRateSelector :model-value="speech.rate.value" @select="speech.setRate" /><button @click="grow">
          <Type /><span>{{ font }}px</span></button
        ><button @click="toggleFav" :class="{ active: favorite }">
          <Heart :fill="favorite ? 'currentColor' : 'none'" /><span>{{
            favorite ? "已收藏" : "收藏"
          }}</span></button
        ><RouterLink :to="{ path: `/original/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"
          ><FileText /><span>查看原文</span></RouterLink
        >
      </nav>
      <div v-if="loading" class="detail-skeleton" aria-label="正在加载详情"><i v-for="n in 5" :key="n"></i></div>
      <section v-else-if="error" class="withdrawn-state" role="status">
        <TriangleAlert /><h2>这条内容当前无法查看</h2><p>{{ error }}</p><RouterLink to="/">返回首页查看其他信息</RouterLink>
      </section>
      <p v-if="speech.error.value" class="warm-tip">{{ speech.error.value }}</p>
      <template v-if="!loading && !error">
      <section class="summary-block">
        <h2>三句话看懂</h2>
        <ol>
          <li v-for="(s, i) in detail.summary">
            <span>{{ i + 1 }}</span>
            <p>{{ s }}</p>
          </li>
        </ol>
      </section>
      <section v-if="targetAudience || eligibility" class="reader-section">
        <h2>我是否符合条件？</h2>
        <div class="answer yes">
          <CheckCircle2 />
          <div>
            <p v-if="targetAudience"><b>适用对象</b>{{ targetAudience }}</p>
            <p v-if="eligibility"><b>申请条件</b>{{ eligibility }}</p>
          </div>
        </div>
      </section>
      <section v-if="detail.materials.length" class="reader-section">
        <h2>需要准备什么？</h2>
        <ul class="material-list">
          <li v-for="m in detail.materials"><CheckCircle2 />{{ m }}</li>
        </ul>
      </section>
      <section v-if="detail.sessions.length" class="reader-section session-section">
        <h2>接种场次</h2>
        <div class="session-list">
          <article v-for="session in detail.sessions" :key="`${session.date}-${session.time}`">
            <CalendarClock />
            <div><b>{{ session.date }}</b><span>{{ session.time }}</span><small>{{ session.location }}</small></div>
            <em v-if="session.needs_human_review">请人工确认</em>
          </article>
        </div>
      </section>
      <section
        v-if="registrationDate || location || contact || fee"
        class="quick-info"
      >
        <article v-if="registrationDate">
          <CalendarClock /><span
            ><small>报名时间</small><b>{{ registrationDate }}</b></span
          >
        </article>
        <article v-if="location" class="address-info">
          <MapPin /><span><small>地点</small><b>{{ location }}</b></span>
          <button type="button" class="copy-address" @click="copyAddress">
            复制地址
          </button>
        </article>
        <p v-if="copyFeedback" class="copy-feedback" role="status">
          {{ copyFeedback }}
        </p>
        <article v-if="contact">
          <Phone /><span
            ><small>咨询电话</small
            ><a v-if="contactHref" :href="contactHref">{{ contact }}</a
            ><b v-else>{{ contact }}</b></span
          >
        </article>
        <article v-if="fee">
          <CheckCircle2 /><span><small>费用</small><b>{{ fee }}</b></span>
        </article>
      </section>
      <section v-if="detail.steps.length" class="reader-section step-section">
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
      <section v-if="detail.warnings.length" class="reader-section">
        <h2>重要提醒</h2>
        <p v-for="warning in detail.warnings" :key="warning" class="warm-tip">
          <TriangleAlert />{{ warning }}
        </p>
      </section>
      <section v-if="Object.keys(detail.terms).length" class="reader-section">
        <h2>专业术语解释</h2>
        <dl v-for="(explanation, term) in detail.terms" :key="term">
          <dt>{{ term }}</dt>
          <dd>{{ explanation }}</dd>
        </dl>
      </section>
      <RouterLink v-if="!isNews" class="ask-assistant-link" :to="{ path: '/assistant', query: { about: item.slug } }"><MessageCircleQuestion /><span><b>问问这个事项</b><small>助手将根据这份已审核材料回答</small></span><ChevronRight /></RouterLink>
      <RouterLink class="original-link" :to="{ path: `/original/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"
        ><FileText /><span
          ><b>查看原文</b
          ><small>共 {{ item.page_count || 1 }} 页，可核对原文内容</small></span
        ><ChevronRight
      /></RouterLink>
      <p class="disclaimer">内容由简达整理并经人工审核，具体要求以权威来源最新规定为准。</p>
      <nav class="detail-action-bar" aria-label="详情操作">
        <button type="button" @click="speech.toggle(speechText)"><Volume2 /><span>{{ speech.status.value === "playing" ? "暂停" : speech.status.value === "paused" ? "继续" : "听全文" }}</span></button>
        <button type="button" @click="grow"><Type /><span>{{ font }}px</span></button>
        <button type="button" @click="toggleFav"><Heart :fill="favorite ? 'currentColor' : 'none'" /><span>{{ favorite ? "已收藏" : "收藏" }}</span></button>
        <RouterLink :to="{ path: `/original/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"><FileText /><span>查看原文</span></RouterLink>
      </nav>
      </template>
    </main>
  </div>
</template>
