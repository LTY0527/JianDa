<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { fetchDetail, fetchNeighbors, recordContentView, setFavorite, type PublicItemNeighbor, type PublicItemNeighbors } from "../api";
import { cleanDisplayTitle, sanitizeDisplayText } from "../content";
import { readerPreferences, recordVisit, saveFavorite } from "../library";
import SpeechRateSelector from "../components/SpeechRateSelector.vue";
import { useSpeechPlayer } from "../composables/useSpeechPlayer";
import { buildTelephoneHref, copyText } from "../utils/contactActions";
import { deduplicateWarnings, sameDisplayText } from "../utils/contentNormalization";
import { articleCover, categoryDefaultCover } from "../utils/coverImage";
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
  ExternalLink,
  Stethoscope,
  Landmark,
} from "lucide-vue-next";
const route = useRoute();
const router = useRouter();
const neighbors = ref<PublicItemNeighbors>({ previous: null, next: null });
const navigationAnnouncement = ref("");
const preferences = readerPreferences();
let loadVersion = 0;
let pointerStart: { x: number; y: number; time: number; id: number } | null = null;
const font = ref(Number(localStorage.getItem("jianda_font") || 18));
const favorite = ref(false);
const loading = ref(true);
const speech = useSpeechPlayer();
const isNews = computed(() => route.path.startsWith("/news/"));
const isHealth = computed(() => item.value.content_kind === "HEALTH_EDUCATION");
const isPolicy = computed(() => item.value.content_kind === "POLICY_NEWS");
const error = ref("");
const copyFeedback = ref("");
const accessibleText = ref("");
const readingMode = ref<"quick" | "complete">("quick");
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
  audienceRules: any;
  serviceSchedule: any;
  conditionalMaterials: any[];
  fees: any[];
  deliveries: any[];
  deadlines: any[];
  amendments: any[];
  whyItMatters: string[];
  actionChecklist: any[];
  keyFacts: any[];
  commonMistakes: string[];
  faq: any[];
  scope: Record<string, any>;
  uncertainties: string[];
}>({ summary: [], materials: [], steps: [], warnings: [], terms: {}, fields: {}, sessions: [],
  audienceRules: { audience: [], conditions: [] }, serviceSchedule: { service_windows: [], closure_rules: [] },
  conditionalMaterials: [], fees: [], deliveries: [], deadlines: [], amendments: [],
  whyItMatters: [], actionChecklist: [], keyFacts: [], commonMistakes: [], faq: [],
  scope: {}, uncertainties: [] });
const targetAudience = computed(() =>
  detail.value.audienceRules.audience?.map((item: any) => item.value).filter(Boolean).join("；")
  || detail.value.fields.TARGET_AUDIENCE || "");
const eligibility = computed(() => {
  const value = detail.value.audienceRules.conditions?.map((item: any) => item.value).filter(Boolean).join("；")
    || detail.value.fields.ELIGIBILITY || "";
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
const isExpired = computed(() => Boolean(item.value.expires_at)
  && new Date(item.value.expires_at).getTime() < Date.now());
const isDeadlinePassed = computed(() => !isExpired.value && Boolean(item.value.deadline_at)
  && new Date(item.value.deadline_at).getTime() < Date.now());
const verificationPending = computed(() => item.value.verification_status === "REVIEW_REQUIRED");
const speechText = computed(() =>
  [
    cleanDisplayTitle(item.value.title),
    ...detail.value.summary,
    ...detail.value.steps.flatMap((step) => step),
  ].join("。"),
);
async function loadDetail(slug: string) {
  const version = ++loadVersion;
  if (preferences.stopSpeechOnNavigation) speech.stop();
  loading.value = true;
  error.value = "";
  copyFeedback.value = "";
  favorite.value = false;
  accessibleText.value = "";
  readingMode.value = "quick";
  neighbors.value = { previous: null, next: null };
  item.value = { id: 0, slug, title: "正在加载…", category: "", source_name: "", published_at: "" };
  detail.value = { summary: [], materials: [], steps: [], warnings: [], terms: {}, fields: {}, sessions: [],
    audienceRules: { audience: [], conditions: [] }, serviceSchedule: { service_windows: [], closure_rules: [] },
    conditionalMaterials: [], fees: [], deliveries: [], deadlines: [], amendments: [], whyItMatters: [],
    actionChecklist: [], keyFacts: [], commonMistakes: [], faq: [], scope: {}, uncertainties: [] };
  try {
    item.value = await fetchDetail(slug);
    if (version !== loadVersion) return;
    void recordContentView(item.value.id).catch(() => undefined);
    fetchNeighbors(slug, preferences.preferSameCategory)
      .then((value) => { if (version === loadVersion) neighbors.value = value; })
      .catch(() => undefined);
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
    detail.value.audienceRules =
      generated.AUDIENCE_RULES && typeof generated.AUDIENCE_RULES === "object"
        ? generated.AUDIENCE_RULES : { audience: [], conditions: [] };
    detail.value.serviceSchedule =
      generated.SERVICE_SCHEDULE && typeof generated.SERVICE_SCHEDULE === "object"
        ? generated.SERVICE_SCHEDULE : { service_windows: [], closure_rules: [] };
    detail.value.conditionalMaterials = Array.isArray(generated.CONDITIONAL_MATERIALS)
      ? generated.CONDITIONAL_MATERIALS : [];
    detail.value.fees = Array.isArray(generated.FEES) ? generated.FEES : [];
    detail.value.deliveries = Array.isArray(generated.RESULT_DELIVERY)
      ? generated.RESULT_DELIVERY : [];
    detail.value.deadlines = Array.isArray(generated.DEADLINE_RULES)
      ? generated.DEADLINE_RULES : [];
    detail.value.amendments = Array.isArray(generated.AMENDMENTS)
      ? generated.AMENDMENTS : [];
    detail.value.whyItMatters = Array.isArray(generated.WHY_IT_MATTERS)
      ? generated.WHY_IT_MATTERS : [];
    detail.value.actionChecklist = Array.isArray(generated.ACTION_CHECKLIST)
      ? generated.ACTION_CHECKLIST : [];
    detail.value.keyFacts = Array.isArray(generated.KEY_FACTS)
      ? generated.KEY_FACTS : [];
    detail.value.commonMistakes = Array.isArray(generated.COMMON_MISTAKES)
      ? generated.COMMON_MISTAKES : [];
    detail.value.faq = Array.isArray(generated.FAQ) ? generated.FAQ : [];
    detail.value.scope = generated.CONTENT_SCOPE && typeof generated.CONTENT_SCOPE === "object"
      ? generated.CONTENT_SCOPE : {};
    detail.value.uncertainties = Array.isArray(generated.UNCERTAINTIES)
      ? generated.UNCERTAINTIES : [];
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
    detail.value.steps = Array.isArray(generated.STEP_CARDS)
      ? generated.STEP_CARDS.map((step: any) => [step.title, step.description])
      : detail.value.sessions.map((session) => [
          `${session.date}场次`,
          `${session.date} ${session.time}，地点：${session.location}。`,
        ]);
    detail.value.warnings = deduplicateWarnings([
      ...(Array.isArray(generated.RISK_WARNING) ? generated.RISK_WARNING : []),
      fieldWarning.value,
    ]);
    detail.value.terms =
      generated.TERM_EXPLANATION &&
      typeof generated.TERM_EXPLANATION === "object"
        ? generated.TERM_EXPLANATION
        : {};
    accessibleText.value = typeof generated.ACCESSIBLE_TEXT === "string"
      ? sanitizeDisplayText(generated.ACCESSIBLE_TEXT) : "";
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
  } catch {
    error.value = "内容暂时无法读取，可能已撤回";
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}

function neighborPath(target: PublicItemNeighbor): string {
  const kind = target.content_kind === "SERVICE_NOTICE" ? "guide" : "news";
  return `/${kind}/${target.slug}`;
}
async function navigateTo(target: PublicItemNeighbor | null) {
  if (!target || loading.value) return;
  if (preferences.stopSpeechOnNavigation) speech.stop();
  navigationAnnouncement.value = `正在打开：${cleanDisplayTitle(target.title)}`;
  await router.push(neighborPath(target));
  window.scrollTo({ top: 0, behavior: "auto" });
}
const interactiveSelector = "a,button,input,textarea,select,label,audio,video,details,summary,[contenteditable],[role='button'],[role='link']";
function closestInteractive(target: EventTarget | null): Element | null {
  return target instanceof Element ? target.closest(interactiveSelector) : null;
}
function clearPointerStart() {
  pointerStart = null;
}
function onKeydown(event: KeyboardEvent) {
  if (!preferences.desktopSideNavigation || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
  if (closestInteractive(event.target)) return;
  if (event.key === "ArrowLeft" && neighbors.value.previous) { event.preventDefault(); navigateTo(neighbors.value.previous); }
  if (event.key === "ArrowRight" && neighbors.value.next) { event.preventDefault(); navigateTo(neighbors.value.next); }
}
function pointerDown(event: PointerEvent) {
  clearPointerStart();
  if (!preferences.mobileSwipeNavigation || window.innerWidth > 768 || !event.isPrimary || event.button !== 0) return;
  if (closestInteractive(event.target)) return;
  pointerStart = { x: event.clientX, y: event.clientY, time: Date.now(), id: event.pointerId };
}
function pointerUp(event: PointerEvent) {
  if (!event.isPrimary || !pointerStart || pointerStart.id !== event.pointerId) return;
  const dx = event.clientX - pointerStart.x;
  const dy = event.clientY - pointerStart.y;
  const duration = Date.now() - pointerStart.time;
  clearPointerStart();
  if (duration > 800 || Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.25) return;
  if (window.getSelection()?.isCollapsed === false) return;
  navigateTo(dx < 0 ? neighbors.value.next : neighbors.value.previous);
}
watch(() => String(route.params.slug), (slug) => {
  clearPointerStart();
  return loadDetail(slug);
}, { immediate: true });
onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => { window.removeEventListener("keydown", onKeydown); speech.stop(); clearPointerStart(); loadVersion += 1; });
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
function fallbackCover(event: Event) {
  const image = event.currentTarget as HTMLImageElement;
  const attempt = Number(image.dataset.fallbackAttempt || "0") + 1;
  image.dataset.fallbackAttempt = String(attempt);
  image.src = categoryDefaultCover(item.value, attempt);
}
function openOfficial() {
  const url = item.value.canonical_url || item.value.source_url;
  if (!url || item.value.original_page_available === false) {
    error.value = "官方原文链接当前已失效";
    return;
  }
  if (window.confirm("即将离开简达并在新标签页打开官方原文，是否继续？")) {
    window.open(url, "_blank", "noopener,noreferrer");
  }
}
</script>
<template>
  <div class="detail-page" :style="{ '--reader-size': font + 'px' }" @pointerdown="pointerDown" @pointerup="pointerUp" @pointercancel="clearPointerStart" @lostpointercapture="clearPointerStart">
    <p class="sr-only" aria-live="polite">{{ navigationAnnouncement }}</p>
    <button v-if="preferences.desktopSideNavigation && neighbors.previous" type="button" class="article-side-nav article-side-nav--previous" :aria-label="`上一篇：${cleanDisplayTitle(neighbors.previous.title)}`" @click="navigateTo(neighbors.previous)">‹<span>{{ cleanDisplayTitle(neighbors.previous.title) }}</span></button>
    <button v-if="preferences.desktopSideNavigation && neighbors.next" type="button" class="article-side-nav article-side-nav--next" :aria-label="`下一篇：${cleanDisplayTitle(neighbors.next.title)}`" @click="navigateTo(neighbors.next)"><span>{{ cleanDisplayTitle(neighbors.next.title) }}</span>›</button>
    <AppTopBar />
    <main class="reader">

      <article class="article-head">
        <img v-if="isNews" class="article-head__cover" :src="articleCover(item)" :alt="item.image_alt_text || `${item.title}配图`" referrerpolicy="no-referrer" @error="fallbackCover" />
        <span class="category-text">{{ item.category }} · {{ isNews ? "权威资讯" : "办事指南" }}</span>
        <h1>{{ cleanDisplayTitle(item.title) }}</h1>
        <div class="source">
          <ShieldCheck /><span
            ><b>{{ item.source_name }}</b
            ><small
              >{{ verificationPending ? "原文更新待复核" : "已通过人工核验" }} ·
              {{ String(item.original_published_at || item.published_at).slice(0, 10) }} 原始发布 ·
              {{ String(item.published_at).slice(0, 10) }} 简达处理</small
            ></span
          >
        </div>
      </article>
      <section v-if="isExpired || isDeadlinePassed || verificationPending" class="lifecycle-notice" role="status">
        <TriangleAlert />
        <div>
          <b v-if="isExpired">信息已过期</b>
          <b v-else-if="isDeadlinePassed">办理或报名已截止</b>
          <b v-else>原文近期有更新，正在重新核验</b>
          <p v-if="isExpired">本页作为历史记录保留，不再出现在首页推荐。请查看官方原文确认最新安排。</p>
          <p v-else-if="isDeadlinePassed">截止时间已经过去，本页仍保留供您核对历史信息。</p>
          <p v-else>更新内容尚未通过新一轮人工审核，当前页面不再标记为“已核验”。</p>
        </div>
      </section>
      <nav class="reader-tools">
        <button @click="speech.toggle(speechText)" :class="{ active: speech.isActive.value }">
          <component :is="speech.status.value === 'playing' ? Pause : Volume2" /><span>{{
            speech.status.value === "playing" ? "暂停" : speech.status.value === "paused" ? "继续" : "听全文"
          }}</span></button
        ><button v-if="speech.isActive.value" @click="speech.stop">
          <Square /><span>停止</span></button
        ><SpeechRateSelector :model-value="speech.rate.value" @select="speech.setRate" /><span v-if="speech.isActive.value" class="speech-progress" role="status">第 {{ speech.progress.value.current }} / {{ speech.progress.value.total }} 段</span><button @click="grow">
          <Type /><span>{{ font }}px</span></button
        ><button @click="toggleFav" :class="{ active: favorite }">
          <Heart :fill="favorite ? 'currentColor' : 'none'" /><span>{{
            favorite ? "已收藏" : "收藏"
          }}</span></button
        ><RouterLink :to="{ path: `/original/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"
          ><FileText /><span>提取文本</span></RouterLink
        >
        <RouterLink v-if="item.original_file_available" :to="{ path: `/original-file/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"><FileText /><span>原{{ String(item.mime_type || '').startsWith('image/') ? '图' : 'PDF' }}</span></RouterLink>
        <button v-if="isNews" type="button" @click="openOfficial"><ExternalLink/><span>官方原文</span></button>
      </nav>
      <div v-if="loading" class="detail-skeleton" aria-label="正在加载详情"><i v-for="n in 5" :key="n"></i></div>
      <section v-else-if="error" class="withdrawn-state" role="status">
        <TriangleAlert /><h2>这条内容当前无法查看</h2><p>{{ error }}</p><RouterLink to="/">返回首页查看其他信息</RouterLink>
      </section>
      <p v-if="speech.error.value" class="warm-tip">{{ speech.error.value }}</p>
      <template v-if="!loading && !error">
      <nav v-if="isNews" class="reading-mode-tabs" aria-label="阅读深度">
        <button type="button" :class="{ active: readingMode === 'quick' }" @click="readingMode = 'quick'">快速看懂</button>
        <button type="button" :class="{ active: readingMode === 'complete' }" @click="readingMode = 'complete'">完整解读</button>
      </nav>
      <section v-if="!isNews || readingMode === 'quick'" class="summary-block">
        <h2>三句话看懂</h2>
        <ol>
          <li v-for="(s, i) in detail.summary">
            <span>{{ i + 1 }}</span>
            <p>{{ sanitizeDisplayText(s) }}</p>
          </li>
        </ol>
      </section>
      <section v-if="isNews && readingMode === 'quick' && (detail.whyItMatters.length || accessibleText)" class="reader-section article-readable">
        <h2>与我有什么关系？</h2>
        <p v-for="paragraph in (detail.whyItMatters.length ? detail.whyItMatters : [accessibleText]).filter(Boolean)" :key="paragraph">{{ sanitizeDisplayText(paragraph) }}</p>
      </section>
      <section v-if="isNews && readingMode === 'quick' && (detail.actionChecklist.length || detail.steps.length)" class="reader-section step-section">
        <h2>今天可以做什么？</h2>
        <ol>
          <li v-for="(action, index) in (detail.actionChecklist.length ? detail.actionChecklist : detail.steps.map((step) => ({ action: step.join('：'), priority: '了解即可' })))" :key="`${action.action}-${index}`">
            <span>{{ index + 1 }}</span><div><h3>{{ action.priority || "了解即可" }}</h3><p>{{ sanitizeDisplayText(action.action) }}</p></div>
          </li>
        </ol>
      </section>
      <section v-if="isHealth" class="reader-section safety-note">
        <h2><Stethoscope/>健康内容提醒</h2>
        <p>出现明显不适、症状持续或加重时，请及时咨询医疗机构。本文仅供健康科普提示，不能替代医生诊疗。</p>
      </section>
      <section v-if="isPolicy" class="reader-section policy-note">
        <h2><Landmark/>政策信息说明</h2>
        <p>{{ detail.fields.ELIGIBILITY || "这是政策资讯，不等同于个人已经取得申请资格。是否需要个人办理，请以官方原文和属地部门通知为准。" }}</p>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && (targetAudience || eligibility)" class="reader-section">
        <h2>我是否符合条件？</h2>
        <div class="answer yes">
          <CheckCircle2 />
          <div>
            <p v-if="targetAudience"><b>适用对象</b>{{ targetAudience }}</p>
            <p v-if="eligibility"><b>申请条件</b>{{ eligibility }}</p>
          </div>
        </div>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.materials.length" class="reader-section">
        <h2>需要准备什么？</h2>
        <ul class="material-list">
          <li v-for="m in detail.materials"><CheckCircle2 />{{ m }}</li>
        </ul>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && (detail.serviceSchedule.service_windows?.length || detail.serviceSchedule.closure_rules?.length)" class="reader-section structured-section">
        <h2>什么时候能办？</h2>
        <div class="service-window-list">
          <article v-for="(window, index) in detail.serviceSchedule.service_windows" :key="index">
            <CalendarClock />
            <div>
              <b>{{ [...(window.days || []), ...(window.dates || [])].join("、") || "开放时段" }}</b>
              <span v-for="time in window.time_ranges" :key="time">{{ time }}</span>
              <small v-if="window.location">{{ window.location }}</small>
              <em v-if="window.unavailable_note">{{ window.unavailable_note }}</em>
            </div>
          </article>
        </div>
        <p v-for="rule in detail.serviceSchedule.closure_rules" :key="rule.value" class="closure-note">
          <TriangleAlert />{{ rule.value }}
        </p>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.conditionalMaterials.length" class="reader-section structured-section">
        <h2>不同人群带什么？</h2>
        <article v-for="group in detail.conditionalMaterials" :key="group.applicable_to" class="conditional-material-card">
          <h3>{{ group.applicable_to }}</h3>
          <p v-if="group.required?.length"><b>必须准备</b>{{ group.required.join("、") }}</p>
          <p v-if="group.optional?.length" class="optional-material"><b>可自愿携带</b>{{ group.optional.join("、") }}</p>
        </article>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.sessions.length" class="reader-section session-section">
        <h2>服务场次</h2>
        <div class="session-list">
          <article v-for="session in detail.sessions" :key="`${session.date}-${session.time}`">
            <CalendarClock />
            <div><b>{{ session.date }}</b><span>{{ session.time }}</span><small>{{ session.location }}</small></div>
            <em v-if="session.needs_human_review">请人工确认</em>
          </article>
        </div>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.fees.length" class="reader-section structured-section">
        <h2>需要多少钱？</h2>
        <article v-for="feeItem in detail.fees" :key="feeItem.fee_type" class="structured-row">
          <div><b>{{ feeItem.fee_type }}</b><p>{{ feeItem.amount || feeItem.rule }}</p></div>
          <small v-if="feeItem.payment_methods?.length">支付方式：{{ feeItem.payment_methods.join("、") }}</small>
        </article>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.deliveries.length" class="reader-section structured-section">
        <h2>怎么办理领取？</h2>
        <article v-for="delivery in detail.deliveries" :key="delivery.method" class="structured-row">
          <div><b>{{ delivery.method }}<em v-if="delivery.optional">（自愿选择）</em></b>
            <p>{{ [delivery.available_after, delivery.location, delivery.fee_rule].filter(Boolean).join("；") }}</p>
          </div>
        </article>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.deadlines.length" class="reader-section structured-section">
        <h2>什么时候前办理？</h2>
        <article v-for="deadline in detail.deadlines" :key="`${deadline.channel}-${deadline.value}`" class="structured-row">
          <div><b>{{ deadline.channel || "期限规则" }}</b><p>{{ deadline.value }}</p></div>
        </article>
      </section>
      <section v-if="(!isNews || readingMode === 'complete') && detail.amendments.length" class="reader-section amendment-section">
        <h2>更正信息</h2>
        <article v-for="amendment in detail.amendments" :key="amendment.corrected_information">
          <p><del>{{ amendment.original_information }}</del></p>
          <p><b>以此为准：</b>{{ amendment.corrected_information }}</p>
          <small>{{ amendment.effective_priority }}</small>
        </article>
      </section>
      <section
        v-if="(!isNews || readingMode === 'complete') && (registrationDate || location || contact || fee)"
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
      <section v-if="(!isNews || readingMode === 'complete') && detail.steps.length" class="reader-section step-section">
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
      <section v-if="detail.warnings.length && (!isNews || readingMode === 'quick')" class="reader-section">
        <h2>重要提醒</h2>
        <p v-for="warning in detail.warnings" :key="warning" class="warm-tip">
          <TriangleAlert />{{ warning }}
        </p>
      </section>
      <template v-if="isNews && readingMode === 'complete'">
        <section v-if="accessibleText" class="reader-section article-readable">
          <h2>背景</h2><p>{{ accessibleText }}</p>
        </section>
        <section v-if="detail.keyFacts.length" class="reader-section">
          <h2>关键事实</h2>
          <dl v-for="fact in detail.keyFacts" :key="`${fact.label}-${fact.value}`"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></dl>
        </section>
        <section v-if="detail.actionChecklist.length" class="reader-section step-section">
          <h2>行动清单</h2><ol><li v-for="(action, index) in detail.actionChecklist" :key="`${action.action}-${index}`"><span>{{index+1}}</span><div><h3>{{action.priority}}</h3><p>{{action.action}}</p><small v-if="action.source_quote">原文依据：{{action.source_quote}}</small></div></li></ol>
        </section>
        <section v-if="detail.commonMistakes.length" class="reader-section"><h2>常见误区</h2><ul class="material-list"><li v-for="mistake in detail.commonMistakes" :key="mistake"><TriangleAlert/>{{mistake}}</li></ul></section>
        <section v-if="detail.faq.length" class="reader-section"><h2>常见问题</h2><dl v-for="entry in detail.faq" :key="entry.question"><dt>{{entry.question}}</dt><dd>{{entry.answer}}</dd></dl></section>
        <section v-if="Object.keys(detail.scope).length" class="reader-section"><h2>适用范围</h2><p>{{ [detail.scope.national_or_local, detail.scope.applicable_region].filter(Boolean).join("；") }}</p><p v-if="detail.scope.needs_personal_action === false">当前无需个人办理。</p></section>
        <section v-if="detail.uncertainties.length" class="reader-section"><h2>尚待确认</h2><p v-for="value in detail.uncertainties" :key="value" class="warm-tip"><TriangleAlert/>{{value}}</p></section>
        <section v-if="detail.warnings.length" class="reader-section"><h2>重要提醒</h2><p v-for="warning in detail.warnings" :key="warning" class="warm-tip"><TriangleAlert/>{{warning}}</p></section>
      </template>
      <section v-if="(!isNews || readingMode === 'complete') && Object.keys(detail.terms).length" class="reader-section">
        <h2>专业术语解释</h2>
        <dl v-for="(explanation, term) in detail.terms" :key="term">
          <dt>{{ term }}</dt>
          <dd>{{ explanation }}</dd>
        </dl>
      </section>
      <RouterLink v-if="!isNews" class="ask-assistant-link" :to="{ path: '/assistant', query: { about: item.slug } }"><MessageCircleQuestion /><span><b>问问这个事项</b><small>助手将根据这份已审核材料回答</small></span><ChevronRight /></RouterLink>
      <RouterLink class="original-link" :to="{ path: `/original/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"
        ><FileText /><span
          ><b>查看提取文本</b
          ><small>共 {{ item.page_count || 1 }} 页，可按字段核对文本</small></span
        ><ChevronRight
      /></RouterLink>
      <RouterLink v-if="item.original_file_available" class="original-link" :to="{ path: `/original-file/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"
        ><FileText /><span><b>查看原{{ String(item.mime_type || '').startsWith('image/') ? '图' : 'PDF' }}</b><small>保留上传文件的分页、表格和排版</small></span><ChevronRight /></RouterLink>
      <button v-if="isNews" type="button" class="original-link official-source-link" @click="openOfficial"><ExternalLink/><span><b>查看官方原文</b><small>{{ item.source_name }} · {{ item.canonical_url || item.source_url }}</small></span><ChevronRight/></button>
      <section v-if="isNews" class="image-source-note"><b>图片来源</b><span>{{ item.image_source_name || "简达本地分类默认图" }}</span><small>{{ item.cover_image_type === "CATEGORY_DEFAULT" ? "本地中性分类插图，不代表原文现场" : item.image_license_note }}</small></section>
      <nav v-if="neighbors.previous || neighbors.next" class="article-neighbors" aria-label="连续阅读">
        <button type="button" :disabled="!neighbors.previous" @click="navigateTo(neighbors.previous)"><small>上一篇</small><b>{{ neighbors.previous ? cleanDisplayTitle(neighbors.previous.title) : "已经是第一篇" }}</b><span>{{ neighbors.previous?.category || "" }}</span></button>
        <button type="button" :disabled="!neighbors.next" @click="navigateTo(neighbors.next)"><small>下一篇</small><b>{{ neighbors.next ? cleanDisplayTitle(neighbors.next.title) : "已经是最后一篇" }}</b><span>{{ neighbors.next?.category || "" }}</span></button>
      </nav>
      <p class="disclaimer">内容由简达整理并经人工审核，具体要求以权威来源最新规定为准。</p>
      <p v-if="speech.isActive.value" class="speech-progress detail-speech-progress" role="status">
        正在朗读第 {{ speech.progress.value.current }} / {{ speech.progress.value.total }} 段
      </p>
      <nav class="detail-action-bar" aria-label="详情操作">
        <button type="button" @click="speech.toggle(speechText)"><Volume2 /><span>{{ speech.status.value === "playing" ? "暂停" : speech.status.value === "paused" ? "继续" : "听全文" }}</span></button>
        <button type="button" @click="grow"><Type /><span>{{ font }}px</span></button>
        <button type="button" @click="toggleFav"><Heart :fill="favorite ? 'currentColor' : 'none'" /><span>{{ favorite ? "已收藏" : "收藏" }}</span></button>
        <RouterLink :to="{ path: `/original/${item.slug}`, query: { from: isNews ? 'news' : 'guide' } }"><FileText /><span>提取文本</span></RouterLink>
      </nav>
      </template>
    </main>
  </div>
</template>
