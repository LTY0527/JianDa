<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { Plus, ShieldCheck, ToggleLeft, ToggleRight, RefreshCw, ArrowRight, Settings2 } from "lucide-vue-next";
import PageHeader from "../components/PageHeader.vue";
import HelpTip from "../components/HelpTip.vue";
import { apiMessage } from "../api/http";
import {
  publicSourceApi,
  type AiQueueItem,
  type ArticleDiscoveryCandidate,
  type ArticleDiscoveryResult,
  type CrawlJob,
  type CoverBackfillJob,
  type PublicSource,
  type QuickSourcePreview,
  type RuntimeCapabilities,
  type SourceRegistryPayload,
  type WebArticlePreview,
  type WebSourceRegistry,
  type BatchImportJob,
} from "../api/publicSources";
import {
  formatDisplayDateTime,
  statusLabel,
} from "../utils/display";

const sources = ref<PublicSource[]>([]);
const router = useRouter();
const registries = ref<WebSourceRegistry[]>([]);
const jobs = ref<CrawlJob[]>([]);
const aiQueue = ref<AiQueueItem[]>([]);
const runtime = ref<RuntimeCapabilities | null>(null);
const capabilityItems = computed(() => {
  if (!runtime.value) return [];
  const payment = runtime.value.payment;
  return [
    { name: "高德地图", status: runtime.value.amap.status, detail: runtime.value.amap.message || "地图配置" },
    { name: "DeepSeek", status: runtime.value.aiService.llm.status, detail: `${runtime.value.aiService.llm.provider || runtime.value.llmProvider} ${runtime.value.aiService.llm.model || runtime.value.externalModel}`.trim() },
    { name: "联网搜索", status: runtime.value.webSearch.status, detail: runtime.value.webSearch.message || runtime.value.webSearch.provider },
    { name: "网页采集", status: runtime.value.aiService.webCollector.status, detail: "安全预览与正文解析" },
    { name: "OCR", status: runtime.value.aiService.ocr.status, detail: runtime.value.aiService.ocr.engine || "文字识别" },
    { name: "支付测试", status: payment.available ? "ready" : "disabled", detail: payment.message },
  ];
});
function capabilityLabel(status: string) {
  return ({ ready: "可用", degraded: "待配置", disabled: "未启用", unreachable: "无法连接" } as Record<string, string>)[status] || status;
}
const selectedJob = ref<(CrawlJob & { result?: BatchImportJob["result"] }) | null>(null);
const taskStatus = ref("");
const taskSourceId = ref<number | undefined>();
const retrying = ref<number | null>(null);
const operatingSourceId = ref<number | null>(null);
const discoveryResult = ref<{ source: WebSourceRegistry; data: ArticleDiscoveryResult } | null>(null);
const discoveryJob = ref<CrawlJob | null>(null);
const activeDiscoverySource = ref<WebSourceRegistry | null>(null);
const shadowPreview = ref<{
  source: WebSourceRegistry;
  article: ArticleDiscoveryCandidate;
  preview: WebArticlePreview;
} | null>(null);
const operationMessage = ref("");
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const showForm = ref(false);
const showAdvanced = ref(false);
const editingRegistryId = ref<number | null>(null);
const activeSection = ref<"sources" | "scan" | "ai" | "jobs" | "advanced">("sources");
const sectionTabs = [
  { id: "sources", label: "来源列表" },
  { id: "scan", label: "扫描与导入" },
  { id: "ai", label: "AI 等待队列" },
  { id: "jobs", label: "采集任务" },
  { id: "advanced", label: "高级自动采集设置" },
] as const;
const selectedUrls = ref<string[]>([]);
const quickUrl = ref("");
const quickPreview = ref<QuickSourcePreview | null>(null);
const quickForm = reactive({
  sourceName: "",
  sourceType: "OTHER_VERIFIED_OFFICIAL",
  verificationNote: "",
  officialConfirmed: false,
  mode: "SAVE_MANUAL_SCAN" as "TEMPORARY_IMPORT" | "SAVE_TRUSTED" | "SAVE_MANUAL_SCAN" | "SAVE_AUTO_SCAN",
  imageUsagePolicy: "MANUAL_REVIEW",
  imageUsageBasis: "",
  autoApproveImages: false,
  imageCacheAllowed: false,
  continueImport: true,
});
const scanForm = reactive({
  recentDays: 7,
  maxArticles: 20,
  includeKeywords: "",
  excludeKeywords: "",
  onlyUnimported: true,
});
const backfillForm = reactive({
  onlyMissing: true,
  sourceId: undefined as number | undefined,
  contentKind: "",
  publishStatus: "",
  fromDate: "",
  toDate: "",
});
const backfillPreview = ref<{ total: number; byType: Record<string, number> } | null>(null);
const backfillJob = ref<CoverBackfillJob | null>(null);
let backfillTimer: ReturnType<typeof setTimeout> | null = null;
let discoveryTimer: ReturnType<typeof setTimeout> | null = null;
const form = reactive({ name: "", type: "GOVERNMENT", url: "https://", publisher: "", notes: "" });
const registryForm = reactive<SourceRegistryPayload>({
  name: "", domain: "", allowedHosts: "", type: "PUBLIC_INSTITUTION", authorityLevel: "B",
  homepageUrl: "https://", rssUrl: "", sitemapUrl: "", sectionUrl: "",
  discoveryMode: "MANUAL", dailyCrawlTime: "03:30", maxArticlesPerRun: 5,
  allowImageCandidates: false, allowAutoAi: false, dailyArticleBudget: 0, dailyTokenBudget: 0,
  scheduleMode: "DAILY", intervalHours: 24, scheduleTimezone: "Asia/Shanghai", recentDays: 7,
  includeKeywords: "", excludeKeywords: "", autoSaveDraft: true, duplicateStrategy: "SKIP",
  maxRetries: 3, imageUsagePolicy: "MANUAL_REVIEW", imageUsageBasis: "",
  autoApproveImages: false, imageCacheAllowed: false,
});
const typeText: Record<string, string> = {
  GOVERNMENT: "政府",
  PUBLIC_INSTITUTION: "事业单位",
  HOSPITAL: "公立医院 / 权威医疗机构",
  COMMUNITY_HEALTH: "社区卫生服务中心",
  CDC: "疾病预防控制机构",
  ELDERLY_SERVICE_ORG: "官方养老服务机构",
  OFFICIAL_MEDIA: "官方或主流媒体",
  OFFICIAL_WECHAT: "官方微信公众号",
  UNIVERSITY_PUBLIC_SERVICE: "高校公共服务",
  OTHER_VERIFIED_OFFICIAL: "其他已核验官方来源",
  MAINSTREAM_MEDIA: "主流媒体（历史）",
  ANTI_FRAUD: "反诈机构（历史）",
  ELDERLY_CARE: "养老机构（历史）",
  OTHER_PUBLIC_SERVICE: "其他公共服务（历史）",
};

function latestJob(source: WebSourceRegistry) {
  return jobs.value.find((job) => job.source_registry_id === source.id);
}
function sourceHealth(source: WebSourceRegistry) {
  if (!source.enabled) return { label: "已停用", note: "管理员已暂停自动更新", tone: "off" };
  const job = latestJob(source);
  if (source.last_error || job?.status === "FAILED") return { label: "需要关注", note: "最近一次检查没有完成", tone: "warning" };
  if (job?.status === "PARTIAL_SUCCESS") return { label: "需要关注", note: "部分页面暂时无法读取", tone: "warning" };
  return { label: "正常", note: source.last_crawled_at ? "最近一次检查正常" : "等待首次检查", tone: "ok" };
}
const enabledSourceCount = computed(() => registries.value.filter((source) => source.enabled).length);
async function checkNow(source: WebSourceRegistry) {
  operatingSourceId.value = source.id;
  error.value = "";
  try {
    const entry = discoveryEntry(source);
    const response = await publicSourceApi.startRegistryDiscoveryJob(source.id, {
      method: entry.method,
      entryUrl: entry.entryUrl,
      ...scanForm,
    });
    await router.push(`/public-sources/${source.id}/check/${response.data.data.id}`);
  } catch (cause) {
    error.value = apiMessage(cause);
    operatingSourceId.value = null;
  }
}
function openNewSource() {
  showAdvanced.value = true;
  activeSection.value = "sources";
  showForm.value = true;
}
function moreForSource(source: WebSourceRegistry) {
  editRegistry(source);
  showAdvanced.value = true;
  activeSection.value = "advanced";
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [sourceResponse, registryResponse, jobResponse, aiQueueResponse, runtimeResponse] = await Promise.all([
      publicSourceApi.sources(),
      publicSourceApi.webRegistries(),
      publicSourceApi.crawlJobs({ status: taskStatus.value || undefined, sourceId: taskSourceId.value }),
      publicSourceApi.aiQueue(),
      publicSourceApi.runtimeCapabilities(),
    ]);
    sources.value = sourceResponse.data.data;
    registries.value = registryResponse.data.data;
    jobs.value = jobResponse.data.data;
    aiQueue.value = aiQueueResponse.data.data;
    runtime.value = runtimeResponse.data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function createSource() {
  saving.value = true;
  error.value = "";
  try {
    await publicSourceApi.createSource(form);
    Object.assign(form, { name: "", type: "GOVERNMENT", url: "https://", publisher: "", notes: "" });
    showForm.value = false;
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

async function toggle(source: PublicSource) {
  try {
    await publicSourceApi.setEnabled(source.id, !source.enabled);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function saveRegistry() {
  saving.value = true;
  error.value = "";
  try {
    if (editingRegistryId.value) await publicSourceApi.updateWebRegistry(editingRegistryId.value, registryForm);
    else await publicSourceApi.createWebRegistry(registryForm);
    editingRegistryId.value = null;
    Object.assign(registryForm, {
      name: "", domain: "", allowedHosts: "", type: "PUBLIC_INSTITUTION", authorityLevel: "B", homepageUrl: "https://",
      rssUrl: "", sitemapUrl: "", sectionUrl: "", discoveryMode: "MANUAL", dailyCrawlTime: "03:30",
      maxArticlesPerRun: 5, allowImageCandidates: false, dailyArticleBudget: 0, dailyTokenBudget: 0,
      allowAutoAi: false, scheduleMode: "DAILY", intervalHours: 24, scheduleTimezone: "Asia/Shanghai",
      recentDays: 7, includeKeywords: "", excludeKeywords: "", autoSaveDraft: true,
      duplicateStrategy: "SKIP", maxRetries: 3, imageUsagePolicy: "MANUAL_REVIEW",
      imageUsageBasis: "", autoApproveImages: false, imageCacheAllowed: false,
    });
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

function editRegistry(source: WebSourceRegistry) {
  editingRegistryId.value = source.id;
  Object.assign(registryForm, {
    name: source.source_name, domain: source.domain, allowedHosts: source.allowed_hosts || "", type: source.source_type,
    authorityLevel: source.authority_level, homepageUrl: source.homepage_url,
    rssUrl: source.rss_url || "", sitemapUrl: source.sitemap_url || "", sectionUrl: source.section_url || "",
    discoveryMode: source.discovery_mode, dailyCrawlTime: source.daily_crawl_time,
    maxArticlesPerRun: source.max_articles_per_run, allowImageCandidates: source.allow_image_candidates,
    allowAutoAi: source.allow_auto_ai, dailyArticleBudget: source.daily_article_budget, dailyTokenBudget: source.daily_token_budget,
    scheduleMode: source.schedule_mode, intervalHours: source.interval_hours,
    scheduleTimezone: source.schedule_timezone, recentDays: source.recent_days,
    includeKeywords: source.include_keywords || "", excludeKeywords: source.exclude_keywords || "",
    autoSaveDraft: source.auto_save_draft, duplicateStrategy: source.duplicate_strategy,
    maxRetries: source.max_retries, imageUsagePolicy: source.image_usage_policy,
    imageUsageBasis: source.image_usage_basis || "", autoApproveImages: source.auto_approve_images,
    imageCacheAllowed: source.image_cache_allowed,
  });
}

async function toggleRegistry(source: WebSourceRegistry) {
  const action = source.enabled ? "停用" : "启用";
  if (!window.confirm(`确认${action}“${source.source_name}”吗？`)) return;
  try {
    await publicSourceApi.setWebRegistryEnabled(source.id, !source.enabled);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function stopJob(job: CrawlJob) {
  if (!window.confirm(`确认取消任务 #${job.id} 吗？`)) return;
  try {
    await publicSourceApi.stopCrawlJob(job.id);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function openJob(job: CrawlJob) {
  try {
    selectedJob.value = job.processing_stage === "BATCH_IMPORT"
      ? (await publicSourceApi.registryImportJob(job.id)).data.data
      : (await publicSourceApi.crawlJob(job.id)).data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function retryError(errorId: number) {
  retrying.value = errorId;
  try {
    await publicSourceApi.retryCrawlError(errorId);
    if (selectedJob.value) await openJob(selectedJob.value);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    retrying.value = null;
  }
}

async function retryFailures(job: CrawlJob) {
  if (!window.confirm(`确认重试任务 #${job.id} 的全部可重试失败项吗？`)) return;
  retrying.value = 0;
  try {
    await publicSourceApi.retryCrawlFailures(job.id);
    await openJob(job);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    retrying.value = null;
  }
}

async function approveQueue(item: AiQueueItem) {
  if (!window.confirm(`确认批准材料 #${item.document_id} 进入 AI 处理队列吗？`)) return;
  try {
    await publicSourceApi.approveAiQueue(item.id);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function retryQueue(item: AiQueueItem) {
  try {
    await publicSourceApi.retryAiQueue(item.id);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function reconcileQueue() {
  try {
    const response = await publicSourceApi.reconcileAiQueue();
    operationMessage.value = `队列重新评估完成：重新排队 ${response.data.data.requeued} 项，保持原状态 ${response.data.data.unchanged} 项。`;
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

function budgetText(value: number | undefined, unit: string) {
  return !value ? "不限" : `${value.toLocaleString()} ${unit}`;
}

function discoveryEntry(source: WebSourceRegistry) {
  const method = source.discovery_mode === "MANUAL" ? "SECTION" : source.discovery_mode;
  const entry = method === "RSS" || method === "ATOM"
    ? source.rss_url
    : method === "SITEMAP"
      ? source.sitemap_url
      : source.section_url || source.homepage_url;
  return { method, entryUrl: entry || source.homepage_url };
}

async function discoverArticles(source: WebSourceRegistry) {
  operatingSourceId.value = source.id;
  error.value = "";
  operationMessage.value = "";
  shadowPreview.value = null;
  activeDiscoverySource.value = source;
  discoveryJob.value = null;
  try {
    const entry = discoveryEntry(source);
    const response = await publicSourceApi.startRegistryDiscoveryJob(source.id, {
      method: entry.method,
      entryUrl: entry.entryUrl,
      ...scanForm,
    });
    applyDiscoveryJob(response.data.data);
  } catch (cause) {
    error.value = apiMessage(cause);
    operatingSourceId.value = null;
  }
}

function applyDiscoveryJob(job: CrawlJob) {
  discoveryJob.value = job;
  if (job.status === "PENDING" || job.status === "RUNNING") {
    operationMessage.value = job.progress_message || `正在检查“${activeDiscoverySource.value?.source_name || "官方来源"}”`;
    if (discoveryTimer) clearTimeout(discoveryTimer);
    discoveryTimer = setTimeout(() => pollDiscoveryJob(job.id), 1200);
    return;
  }
  operatingSourceId.value = null;
  if ((job.status === "SUCCESS" || job.status === "PARTIAL_SUCCESS") && job.discoveryResult && activeDiscoverySource.value) {
    discoveryResult.value = { source: activeDiscoverySource.value, data: job.discoveryResult };
    selectedUrls.value = [];
    operationMessage.value = `检查完成：发现 ${job.discovered_count} 篇，已有 ${job.duplicate_count} 篇，新增 ${job.added_count} 篇。未创建材料、未调用 AI。`;
  } else if (job.status === "FAILED") {
    operationMessage.value = "";
  }
  void load();
}

async function pollDiscoveryJob(jobId: number) {
  try {
    const response = await publicSourceApi.registryDiscoveryJob(jobId);
    applyDiscoveryJob(response.data.data);
  } catch (cause) {
    operatingSourceId.value = null;
    error.value = apiMessage(cause);
  }
}

function failureAdvice(code?: string) {
  if (["CONNECT_TIMEOUT", "READ_TIMEOUT", "DNS_FAILED", "HTTP_429", "HTTP_5XX", "ROBOTS_UNAVAILABLE"].includes(code || "")) {
    return "系统会保留失败记录，可稍后重新检查；也可以直接粘贴官方文章地址继续工作。";
  }
  if (code === "DYNAMIC_PAGE_SUSPECTED" || code === "NO_ARTICLE_LINKS" || code === "PARSER_UNSUPPORTED") {
    return "可改用具体文章 URL、粘贴正文或上传官方 PDF，不必等待自动扫描修复。";
  }
  return "请核对来源入口配置，或改用手动导入文章。";
}

function selectAllUnimported() {
  selectedUrls.value = discoveryResult.value?.data.candidates
    .filter((item) => !item.imported && item.relevance_level !== "LOW")
    .map((item) => item.canonical_url) || [];
}

async function collectSelected() {
  if (!discoveryResult.value || selectedUrls.value.length === 0) return;
  if (!window.confirm(`确认保存所选 ${selectedUrls.value.length} 篇为材料并加入 AI 等待队列吗？不会自动发布。`)) return;
  operatingSourceId.value = discoveryResult.value.source.id;
  try {
    const response = await publicSourceApi.collectRegistryArticles(
      discoveryResult.value.source.id,
      selectedUrls.value,
    );
    operationMessage.value = `批量加入任务 #${response.data.data.jobId} 已创建，将在后台继续处理；可到任务中心查看进度。`;
    selectedUrls.value = [];
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    operatingSourceId.value = null;
  }
}

async function previewQuickSource() {
  if (!quickUrl.value.trim()) return;
  saving.value = true;
  error.value = "";
  quickPreview.value = null;
  try {
    const response = await publicSourceApi.quickPreviewSource(quickUrl.value.trim());
    quickPreview.value = response.data.data;
    quickForm.sourceName = quickPreview.value.wechat_account_name
      || quickPreview.value.source_name || quickPreview.value.domain;
    quickForm.sourceType = quickPreview.value.source_type_suggestion;
    operationMessage.value = "安全预览完成。该结果仅用于核对，尚未将来源标记为权威。";
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

async function confirmQuickSource() {
  if (!quickPreview.value) return;
  saving.value = true;
  error.value = "";
  try {
    const response = await publicSourceApi.quickConfirmSource({
      url: quickUrl.value.trim(),
      ...quickForm,
    });
    const imported = response.data.data.imported;
    operationMessage.value = imported
      ? `来源身份已确认，材料 #${imported.documentId} 已创建并进入 ${statusLabel(imported.aiQueueStatus)}。`
      : "来源身份已确认并保存。";
    quickPreview.value = null;
    quickUrl.value = "";
    Object.assign(quickForm, {
      sourceName: "", sourceType: "OTHER_VERIFIED_OFFICIAL", verificationNote: "",
      officialConfirmed: false, mode: "SAVE_MANUAL_SCAN", imageUsagePolicy: "MANUAL_REVIEW",
      imageUsageBasis: "", autoApproveImages: false, imageCacheAllowed: false, continueImport: true,
    });
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

async function previewBackfill() {
  saving.value = true;
  error.value = "";
  try {
    const response = await publicSourceApi.previewCoverBackfill(backfillForm);
    backfillPreview.value = response.data.data;
    operationMessage.value = `预览完成：共 ${response.data.data.total} 条历史内容符合补图条件，尚未修改数据。`;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

async function executeBackfill() {
  if (!backfillPreview.value || !window.confirm(
    `确认处理 ${backfillPreview.value.total} 条历史内容吗？第三方图片仍按来源策略决定自动确认或进入人工审核。`,
  )) return;
  saving.value = true;
  error.value = "";
  try {
    const response = await publicSourceApi.startCoverBackfillJob(backfillForm);
    backfillJob.value = response.data.data;
    operationMessage.value = `历史补图任务 #${backfillJob.value.jobId} 已启动。`;
    backfillPreview.value = null;
    scheduleBackfillPoll();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

function scheduleBackfillPoll() {
  if (backfillTimer) clearTimeout(backfillTimer);
  if (!backfillJob.value || !["PENDING", "RUNNING"].includes(backfillJob.value.status)) {
    return;
  }
  backfillTimer = setTimeout(async () => {
    if (!backfillJob.value) return;
    try {
      backfillJob.value = (
        await publicSourceApi.coverBackfillJob(backfillJob.value.jobId)
      ).data.data;
      if (["PENDING", "RUNNING"].includes(backfillJob.value.status)) {
        scheduleBackfillPoll();
      } else {
        await load();
      }
    } catch (cause) {
      error.value = apiMessage(cause);
    }
  }, 1500);
}

async function retryBackfillItem(documentId: number) {
  if (!backfillJob.value) return;
  try {
    backfillJob.value = (
      await publicSourceApi.retryCoverBackfillItem(
        backfillJob.value.jobId,
        documentId,
      )
    ).data.data;
    scheduleBackfillPoll();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function shadowArticle(source: WebSourceRegistry, article: ArticleDiscoveryCandidate) {
  operatingSourceId.value = source.id;
  error.value = "";
  operationMessage.value = "";
  try {
    const response = await publicSourceApi.shadowRegistryArticle(source.id, article.canonical_url);
    shadowPreview.value = { source, article, preview: response.data.data };
    operationMessage.value = "影子采集完成：已抓取正文和图片候选预览，未创建材料、未调用 AI、未发布。";
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    operatingSourceId.value = null;
  }
}

async function collectArticle(source: WebSourceRegistry, article: ArticleDiscoveryCandidate) {
  if (!window.confirm(`确认采集“${article.title || article.canonical_url}”并创建待审核材料吗？不会自动发布。`)) return;
  operatingSourceId.value = source.id;
  error.value = "";
  operationMessage.value = "";
  try {
    const response = await publicSourceApi.collectRegistryArticle(source.id, article.canonical_url);
    const result = response.data.data;
    operationMessage.value = `材料 #${result.documentId} 已创建，AI 队列状态：${statusLabel(result.aiQueueStatus)}。`;
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    operatingSourceId.value = null;
  }
}

onMounted(load);
onUnmounted(() => {
  if (backfillTimer) clearTimeout(backfillTimer);
  if (discoveryTimer) clearTimeout(discoveryTimer);
});
</script>

<template>
  <div>
    <PageHeader title="采集与来源" :description="`已配置 ${registries.length} 个公开来源，其中 ${enabledSourceCount} 个正在自动更新。`">
      <button class="btn primary" @click="openNewSource"><Plus :size="17" />新增来源</button>
    </PageHeader>
    <div v-if="error" class="inline-error">{{ error }}</div>
    <section v-if="discoveryJob?.status === 'PENDING' || discoveryJob?.status === 'RUNNING'" class="source-operation-state running" aria-live="polite">
      <RefreshCw class="spin" :size="22" />
      <div><b>{{ discoveryJob.progress_message || "正在连接官网" }}</b><p>检查任务 #{{ discoveryJob.id }} 已在后台运行，可以留在当前页面查看结果。</p></div>
    </section>
    <section v-if="discoveryJob?.status === 'FAILED'" class="source-operation-state failed" role="alert">
      <div>
        <b>本次检查没有完成</b>
        <p>原因：{{ discoveryJob.errors?.[0]?.error_summary || discoveryJob.last_error || "官网暂时无法读取" }}</p>
        <small>{{ failureAdvice(discoveryJob.errors?.[0]?.error_code) }}</small>
        <details v-if="discoveryJob.errors?.[0]?.error_code"><summary>查看技术原因</summary><code>{{ discoveryJob.errors[0].error_code }}</code></details>
      </div>
      <div class="source-operation-actions">
        <button class="btn primary" type="button" :disabled="!activeDiscoverySource" @click="activeDiscoverySource && discoverArticles(activeDiscoverySource)">重新检查</button>
        <button class="btn secondary" type="button" @click="activeSection = 'scan'; showAdvanced = true">手动导入文章</button>
      </div>
    </section>

    <section class="source-overview" aria-label="自动采集来源">
      <article v-for="source in registries" :key="source.id" class="source-card">
        <header><div><h2>{{ source.source_name }}</h2><p>{{ source.domain }}</p></div><span :class="sourceHealth(source).tone">{{ sourceHealth(source).label }} <HelpTip term="sourceHealth" label="来源健康状态说明" /></span></header>
        <dl>
          <div><dt>上次检查</dt><dd>{{ formatDisplayDateTime(source.last_crawled_at) }}</dd></div>
          <div><dt>发现新内容</dt><dd>{{ latestJob(source)?.added_count || 0 }} 篇</dd></div>
          <div><dt>下次检查</dt><dd>{{ source.enabled ? formatDisplayDateTime(source.next_run_at) : "暂停中" }}</dd></div>
        </dl>
        <p class="source-health-note">{{ sourceHealth(source).note }}</p>
        <div class="source-auto-state"><b>{{ source.enabled ? "自动更新已开启" : "自动更新已关闭" }}</b><HelpTip term="automaticUpdate" label="自动更新说明" /></div>
        <footer><button class="btn secondary" type="button" :disabled="!source.enabled || operatingSourceId === source.id" @click="checkNow(source)"><RefreshCw />立即检查</button><RouterLink class="text-action strong" :to="{ path: '/documents', query: { status: 'WAITING_REVIEW' } }">查看新内容<ArrowRight /></RouterLink><button class="text-action" type="button" @click="moreForSource(source)"><Settings2 />更多</button></footer>
      </article>
      <div v-if="loading" class="empty-state">正在读取来源状态…</div>
      <div v-else-if="!registries.length" class="empty-state"><b>还没有自动采集来源</b><p>新增并核验官方来源后，可在这里查看检查状态。</p></div>
    </section>

    <section class="runtime-strip" aria-label="运行能力诊断">
      <header><div><b>运行能力</b><span>只显示可用状态，不展示密钥和敏感配置</span></div><button type="button" class="text-action" @click="load"><RefreshCw />重新检测</button></header>
      <div v-if="capabilityItems.length" class="runtime-grid">
        <article v-for="item in capabilityItems" :key="item.name" :class="`runtime-${item.status}`">
          <span>{{ item.name }}</span><b>{{ capabilityLabel(item.status) }}</b><small>{{ item.detail }}</small>
        </article>
      </div>
      <div v-else class="empty-state compact">正在检查运行能力…</div>
    </section>

    <button class="advanced-toggle" type="button" :aria-expanded="showAdvanced" @click="showAdvanced = !showAdvanced"><Settings2 />{{ showAdvanced ? "收起高级管理" : "高级管理" }}<span>来源核验、扫描范围、AI 预算和任务记录</span></button>

    <div v-if="showAdvanced" class="collection-advanced">

    <nav class="source-tabs" aria-label="来源管理分区">
      <button v-for="tab in sectionTabs" :key="tab.id" type="button" :class="{ active: activeSection === tab.id }"
        @click="activeSection = tab.id">
        {{ tab.label }}
      </button>
    </nav>

    <form v-if="showForm && activeSection === 'sources'" class="panel source-create" @submit.prevent="createSource">
      <div class="form-row">
        <label class="field">来源名称<input v-model="form.name" required placeholder="例如：市卫生健康委员会" /></label>
        <label class="field">来源类型<select v-model="form.type"><option v-for="(label, value) in typeText" :key="value" :value="value">{{ label }}</option></select></label>
      </div>
      <div class="form-row">
        <label class="field">来源 URL<input v-model="form.url" required type="url" /></label>
        <label class="field">发布机构<input v-model="form.publisher" required /></label>
      </div>
      <label class="field">备注<textarea v-model="form.notes" rows="2" /></label>
      <div class="form-actions"><button type="button" class="btn secondary" @click="showForm = false">取消</button><button class="btn primary" :disabled="saving">{{ saving ? "正在保存…" : "保存来源" }}</button></div>
    </form>

    <section v-show="activeSection === 'sources'" class="panel">
      <table class="data-table source-table">
        <thead><tr><th>来源</th><th>类型</th><th>白名单</th><th>状态</th><th>最近导入</th><th>操作</th></tr></thead>
        <tbody v-if="!loading">
          <tr v-for="source in sources" :key="source.id">
            <td><b>{{ source.source_name }}</b><small>{{ source.publisher }} · {{ source.source_url }}</small></td>
            <td>{{ typeText[source.source_type] || source.source_type }}</td>
            <td><span class="verified"><ShieldCheck :size="15" />已批准</span></td>
            <td>{{ source.enabled ? "已启用" : "已停用" }}</td>
            <td>{{ source.last_imported_at || "尚未导入" }}</td>
            <td><button class="text-action" @click="toggle(source)"><ToggleRight v-if="source.enabled" :size="18" /><ToggleLeft v-else :size="18" />{{ source.enabled ? "停用" : "启用" }}</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">正在加载权威来源…</div>
      <div v-else-if="sources.length === 0" class="empty-state">暂无权威来源，请先新增。</div>
    </section>
    <section v-show="activeSection === 'scan'" class="panel quick-source-panel">
      <div class="panel-title">
        <div>
          <h2>粘贴官方文章 URL</h2>
          <p>先执行 SSRF、DNS/IP、robots、重定向、大小和 MIME 安全检查，再由平台管理员确认官方身份。</p>
        </div>
      </div>
      <div class="quick-url-row">
        <label class="field">公开文章地址
          <input v-model="quickUrl" type="url" placeholder="https://官方站点/文章" />
        </label>
        <button class="btn primary" type="button" :disabled="saving || !quickUrl" @click="previewQuickSource">
          {{ saving ? "正在安全预览…" : "安全预览来源身份" }}
        </button>
      </div>
      <form v-if="quickPreview" class="identity-review" @submit.prevent="confirmQuickSource">
        <div class="identity-summary">
          <div><span>页面标题</span><b>{{ quickPreview.page_title }}</b></div>
          <div><span>域名</span><b>{{ quickPreview.domain }}</b></div>
          <div><span>Canonical</span><b>{{ quickPreview.canonical_url }}</b></div>
          <div><span>HTTPS / robots</span><b>{{ quickPreview.https ? "HTTPS" : "普通 HTTP" }} · {{ quickPreview.robots_status }}</b></div>
          <div v-if="quickPreview.wechat_article"><span>公众号名称</span><b>{{ quickPreview.wechat_account_name || "未提取，请人工核对" }}</b></div>
          <div v-if="quickPreview.wechat_article"><span>发布主体 / 账号标识</span><b>{{ quickPreview.account_subject || "未提取" }} · {{ quickPreview.wechat_biz || "未提取" }}</b></div>
        </div>
        <p v-if="quickPreview.wechat_article" class="safe-note">
          mp.weixin.qq.com 是共享文章域名，平台不会仅凭域名认定官方账号；必须核对公众号名称、发布主体或稳定账号标识。
        </p>
        <div class="form-row">
          <label class="field">来源名称<input v-model="quickForm.sourceName" required /></label>
          <label class="field">来源类型<select v-model="quickForm.sourceType"><option v-for="(label, value) in typeText" :key="value" :value="value">{{ label }}</option></select></label>
        </div>
        <label class="field">官方性质核对说明<textarea v-model="quickForm.verificationNote" required rows="2" placeholder="说明核对的机构官网、账号主体或公开证明" /></label>
        <div class="form-row">
          <label class="field">保存方式<select v-model="quickForm.mode">
            <option value="TEMPORARY_IMPORT">仅本次临时导入</option>
            <option value="SAVE_TRUSTED">保存为可信来源</option>
            <option value="SAVE_MANUAL_SCAN">保存并允许后续手动扫描</option>
            <option value="SAVE_AUTO_SCAN">保存并配置自动定时扫描</option>
          </select></label>
          <label class="field">图片策略<select v-model="quickForm.imageUsagePolicy">
            <option value="MANUAL_REVIEW">逐篇人工确认</option>
            <option value="ORGANIZATION_OWNED">机构自有</option>
            <option value="OFFICIAL_PUBLICITY">官方公开宣传材料</option>
            <option value="AUTHORIZED">已有使用授权</option>
            <option value="LOCAL_DEMO_CONFIRMED">本地演示且已人工确认</option>
          </select></label>
        </div>
        <label v-if="quickForm.imageUsagePolicy !== 'MANUAL_REVIEW'" class="field">图片使用依据
          <textarea v-model="quickForm.imageUsageBasis" rows="2" required />
        </label>
        <label class="check-line"><input v-model="quickForm.officialConfirmed" type="checkbox" required /> 我已核对并确认该账号或站点属于所填官方机构</label>
        <label class="check-line"><input v-model="quickForm.continueImport" type="checkbox" /> 保存来源后继续创建材料（仍需 AI、图片和内容人工审核）</label>
        <div class="form-actions"><button class="btn primary" :disabled="saving || !quickForm.officialConfirmed">确认来源并继续</button></div>
      </form>
    </section>

    <section v-show="['sources','scan','advanced'].includes(activeSection)" class="panel">
      <div class="panel-title"><div><h2>网页白名单来源</h2><p>维护调度入口、文章上限和自动处理预算；新来源默认停用。</p></div></div>
      <form v-show="activeSection !== 'scan'" class="source-create" @submit.prevent="saveRegistry">
        <p class="safe-note">默认安全策略：新来源保持停用，原图缓存和自动 AI 均关闭，所有候选内容必须人工审核后才能发布。</p>
        <div class="form-row">
          <label class="field">来源名称<input v-model="registryForm.name" required /></label>
          <label class="field">完整域名<input v-model="registryForm.domain" required placeholder="www.example.gov.cn" /></label>
        </div>
        <label class="field">
          附加允许域名
          <input
            v-model="registryForm.allowedHosts"
            placeholder="多个域名用逗号分隔；仅填写明确属于该来源的域名"
          />
        </label>
        <div class="form-row">
          <label class="field">来源类型<select v-model="registryForm.type"><option v-for="(label, value) in typeText" :key="value" :value="value">{{ label }}</option></select></label>
          <label class="field">发现方式<select v-model="registryForm.discoveryMode"><option value="MANUAL">手动</option><option value="RSS">RSS</option><option value="ATOM">Atom</option><option value="SITEMAP">Sitemap</option><option value="SECTION">栏目页</option><option value="MIXED">混合</option></select></label>
        </div>
        <label class="field">主页地址<input v-model="registryForm.homepageUrl" type="url" required /></label>
        <div class="form-row"><label class="field">RSS / Atom 地址<input v-model="registryForm.rssUrl" type="url" /></label><label class="field">Sitemap 地址<input v-model="registryForm.sitemapUrl" type="url" /></label></div>
        <label class="field">栏目页地址<input v-model="registryForm.sectionUrl" type="url" /></label>
        <div class="form-row"><label class="field">每日采集时间<input v-model="registryForm.dailyCrawlTime" type="time" required /></label><label class="field">每轮文章上限<input v-model.number="registryForm.maxArticlesPerRun" type="number" min="1" max="100" /></label></div>
        <div class="form-row"><label class="field">每日文章预算<input v-model.number="registryForm.dailyArticleBudget" type="number" min="0" /></label><label class="field">每日 Token 预算<input v-model.number="registryForm.dailyTokenBudget" type="number" min="0" /></label></div>
        <label class="field"><input v-model="registryForm.allowImageCandidates" type="checkbox" /> 允许生成图片候选（仍需人工确认版权）</label>
        <label class="field"><input v-model="registryForm.allowAutoAi" type="checkbox" /> 允许自动 AI（仅在全局开关和预算同时允许时生效）</label>
        <fieldset v-if="activeSection === 'advanced'" class="advanced-settings">
          <legend>高级自动采集设置</legend>
          <p class="safe-note">自动扫描和自动 AI 均按来源单独开启；自动审核与自动发布始终禁止。</p>
          <div class="form-row">
            <label class="field">调度方式<select v-model="registryForm.scheduleMode"><option value="DAILY">每日固定时间</option><option value="INTERVAL">按小时间隔</option></select></label>
            <label class="field">每隔小时数<input v-model.number="registryForm.intervalHours" type="number" min="1" max="168" /></label>
          </div>
          <div class="form-row">
            <label class="field">时区<input v-model="registryForm.scheduleTimezone" /></label>
            <label class="field">扫描最近天数<select v-model.number="registryForm.recentDays"><option :value="1">1 天</option><option :value="3">3 天</option><option :value="7">7 天</option><option :value="30">30 天</option></select></label>
          </div>
          <div class="form-row"><label class="field">包含关键词<input v-model="registryForm.includeKeywords" /></label><label class="field">排除关键词<input v-model="registryForm.excludeKeywords" /></label></div>
          <div class="form-row">
            <label class="field">重复策略<select v-model="registryForm.duplicateStrategy"><option value="SKIP">跳过重复</option><option value="CREATE_VERSION">检测变化并创建新版本</option></select></label>
            <label class="field">失败重试次数<input v-model.number="registryForm.maxRetries" type="number" min="0" max="10" /></label>
          </div>
          <label class="check-line"><input v-model="registryForm.autoSaveDraft" type="checkbox" /> 自动保存草稿（不会自动审核或发布）</label>
          <div class="form-row">
            <label class="field">图片使用策略<select v-model="registryForm.imageUsagePolicy"><option value="MANUAL_REVIEW">逐篇人工确认</option><option value="ORGANIZATION_OWNED">机构自有</option><option value="OFFICIAL_PUBLICITY">官方公开宣传材料</option><option value="AUTHORIZED">已有使用授权</option><option value="LOCAL_DEMO_CONFIRMED">本地演示且已人工确认</option></select></label>
            <label class="field">图片使用依据<input v-model="registryForm.imageUsageBasis" /></label>
          </div>
          <label class="check-line"><input v-model="registryForm.autoApproveImages" type="checkbox" /> 符合本来源策略时自动确认图片</label>
          <label class="check-line"><input v-model="registryForm.imageCacheAllowed" type="checkbox" /> 允许审核通过后缓存图片到本地</label>
        </fieldset>
        <div class="form-actions"><button v-if="editingRegistryId" type="button" class="btn secondary" @click="editingRegistryId = null">取消编辑</button><button class="btn primary" :disabled="saving">{{ saving ? "正在保存…" : editingRegistryId ? "保存修改" : "新增运营来源" }}</button></div>
      </form>
      <section v-if="activeSection === 'advanced'" class="backfill-panel">
        <div class="panel-title">
          <div><h2>历史封面补齐</h2><p>先预览范围，再批量执行；PDF 第一页和已上传原图可自动确认，第三方网页图片遵循来源策略。</p></div>
        </div>
        <div class="form-row">
          <label class="field">来源<select v-model="backfillForm.sourceId"><option :value="undefined">全部来源</option><option v-for="source in registries" :key="source.id" :value="source.id">{{ source.source_name }}</option></select></label>
          <label class="field">内容类型<input v-model="backfillForm.contentKind" placeholder="留空表示全部" /></label>
          <label class="field">发布状态<select v-model="backfillForm.publishStatus"><option value="">全部状态</option><option value="PUBLISHED">已发布</option><option value="DRAFT">草稿</option></select></label>
        </div>
        <div class="form-row">
          <label class="field">开始日期<input v-model="backfillForm.fromDate" type="date" /></label>
          <label class="field">结束日期<input v-model="backfillForm.toDate" type="date" /></label>
        </div>
        <label class="check-line"><input v-model="backfillForm.onlyMissing" type="checkbox" /> 仅处理无封面或仍使用分类默认图的内容</label>
        <div v-if="backfillPreview" class="safe-note">
          待处理 {{ backfillPreview.total }} 条：
          网页 {{ backfillPreview.byType.WEB_ARTICLE || 0 }}，
          PDF {{ backfillPreview.byType.PDF || 0 }}，
          图片 {{ backfillPreview.byType.IMAGE || 0 }}。
        </div>
        <div v-if="backfillJob" class="backfill-progress" role="status">
          <b>任务 #{{ backfillJob.jobId }} · {{ statusLabel(backfillJob.status) }}</b>
          <progress
            :max="Math.max(1, backfillJob.total)"
            :value="backfillJob.processed"
          />
          <p>
            已处理 {{ backfillJob.processed }}/{{ backfillJob.total }} ·
            更新 {{ backfillJob.updated }} · 新增候选
            {{ backfillJob.candidatesCreated }} · 自动确认
            {{ backfillJob.autoApproved }} · 失败 {{ backfillJob.failed }}
          </p>
          <small v-if="backfillJob.currentDocumentId">
            当前：#{{ backfillJob.currentDocumentId }}
            {{ backfillJob.currentDocumentTitle }}
          </small>
          <ul v-if="backfillJob.errors.length">
            <li v-for="item in backfillJob.errors" :key="item.documentId">
              #{{ item.documentId }} {{ item.message }}
              <button
                class="text-action"
                type="button"
                @click="retryBackfillItem(item.documentId)"
              >
                单条重试
              </button>
            </li>
          </ul>
        </div>
        <div class="form-actions">
          <button class="btn secondary" type="button" :disabled="saving" @click="previewBackfill">预览补图范围</button>
          <button class="btn primary" type="button" :disabled="saving || !backfillPreview" @click="executeBackfill">执行历史补图</button>
        </div>
      </section>
      <div v-if="activeSection === 'scan'" class="scan-settings">
        <div class="form-row">
          <label class="field">最近范围<select v-model.number="scanForm.recentDays"><option :value="1">1 天</option><option :value="3">3 天</option><option :value="7">7 天</option><option :value="30">30 天</option></select></label>
          <label class="field">最多发现篇数<input v-model.number="scanForm.maxArticles" type="number" min="1" max="100" /></label>
        </div>
        <div class="form-row"><label class="field">关键词<input v-model="scanForm.includeKeywords" /></label><label class="field">排除关键词<input v-model="scanForm.excludeKeywords" /></label></div>
        <label class="check-line"><input v-model="scanForm.onlyUnimported" type="checkbox" /> 只显示未导入内容</label>
        <p class="safe-note">发现文章只扫描 URL，不创建材料、不调用 AI；影子采集仅抓取预览；立即或批量采集才创建材料，但不会自动发布。</p>
      </div>
      <table class="data-table">
        <thead><tr><th>来源</th><th>调度</th><th>预算</th><th>最近状态</th><th>错误摘要</th><th>操作</th></tr></thead>
        <tbody v-if="!loading">
          <tr v-for="source in registries" :key="source.id">
            <td><b>{{source.source_name}}</b><small>{{source.domain}} · {{typeText[source.source_type] || source.source_type}}</small></td>
            <td>{{source.enabled ? "已启用" : "已停用"}} · {{source.discovery_mode}}<small>{{source.daily_crawl_time}} / 每轮 {{source.max_articles_per_run}} 篇</small></td>
            <td>每日文章 {{budgetText(source.daily_article_budget, "篇")}}<small>Token {{budgetText(source.daily_token_budget, "Token")}} · 当前来源自动 AI {{source.allow_auto_ai ? "允许" : "未允许"}}</small></td>
            <td>{{statusLabel(source.last_status)}}<small>最近 {{formatDisplayDateTime(source.last_crawled_at)}} · 下次 {{formatDisplayDateTime(source.next_run_at)}}</small></td>
            <td>{{source.last_error||"无"}}</td>
            <td>
              <button class="text-action" @click="editRegistry(source)">编辑</button>
              <button class="text-action" @click="toggleRegistry(source)">{{source.enabled ? "停用" : "启用"}}</button>
              <button class="btn secondary scan-action" :disabled="!source.enabled || operatingSourceId === source.id" :title="source.enabled ? '只发现 URL，不创建材料' : '请先启用已核验来源'" @click="activeSection = 'scan'; discoverArticles(source)">扫描最近文章</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">正在加载运营来源…</div>
      <div v-else-if="registries.length === 0" class="empty-state">暂无运营来源，请先新增。</div>
      <div v-if="operationMessage" class="safe-note" role="status">{{ operationMessage }}</div>
      <section v-if="activeSection === 'scan' && discoveryResult" class="source-create controlled-crawl">
        <div class="panel-title">
          <div>
            <h2>{{ discoveryResult.source.source_name }} · 受控采集验收</h2>
            <p>发现文章只列出 URL；影子采集只生成预览；立即采集才创建材料并进入 AI 等待审批队列。</p>
          </div>
          <button class="btn secondary" type="button" @click="discoveryResult = null; shadowPreview = null">关闭</button>
        </div>
        <div v-if="discoveryResult.data.errors.length" class="inline-error">
          {{ discoveryResult.data.errors.join("；") }}
        </div>
        <div
          v-if="discoveryResult.data.filtered_external_count"
          class="safe-note"
        >
          已过滤 {{ discoveryResult.data.filtered_external_count }}
          个不属于当前来源范围的外部链接。
        </div>
        <div
          v-if="discoveryResult.data.filtered_navigation_count"
          class="safe-note"
        >
          已过滤 {{ discoveryResult.data.filtered_navigation_count }} 条导航或目录链接。
        </div>
        <table class="data-table">
          <thead><tr><th>选择</th><th>发现文章</th><th>居民相关度</th><th>方式</th><th>状态</th><th>受控操作</th></tr></thead>
          <tbody>
            <tr v-for="article in discoveryResult.data.candidates" :key="article.dedup_key">
              <td><input v-model="selectedUrls" type="checkbox" :value="article.canonical_url" :disabled="article.imported" :aria-label="`选择${article.title || '文章'}`" /></td>
              <td><b>{{ article.title || "标题待抓取" }}</b><small>{{ article.canonical_url }}</small></td>
              <td><b>{{ article.relevance_level === "HIGH" ? "高" : article.relevance_level === "LOW" ? "低" : "中" }}</b><small>{{ article.recommendation_reason || "建议人工核对" }}</small></td>
              <td>{{ article.discovery_method }}</td>
              <td>{{ article.imported ? "已导入 / 已有版本" : article.published_time || "发布时间待核对" }}</td>
              <td>
                <button class="text-action" :disabled="operatingSourceId !== null" @click="shadowArticle(discoveryResult.source, article)">影子采集</button>
                <button class="text-action strong" :disabled="operatingSourceId !== null" @click="collectArticle(discoveryResult.source, article)">立即采集</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="form-actions batch-actions">
          <button class="btn secondary" type="button" @click="selectAllUnimported">全选未重复内容</button>
          <button class="btn primary" type="button" :disabled="selectedUrls.length === 0 || operatingSourceId !== null" @click="collectSelected">批量保存所选并加入 AI 队列（{{ selectedUrls.length }}）</button>
        </div>
        <div v-if="!discoveryResult.data.candidates.length" class="empty-state">没有发现可验收的文章 URL，请检查入口配置和错误摘要。</div>
        <article v-if="shadowPreview" class="shadow-preview">
          <span class="verified">影子预览 · 未落库</span>
          <h3>{{ shadowPreview.preview.title }}</h3>
          <p>{{ shadowPreview.preview.content_preview }}</p>
          <small>
            {{ shadowPreview.preview.source_name }} · {{ shadowPreview.preview.content_kind }} ·
            图片策略 {{ shadowPreview.preview.cover_image_type }}
          </small>
        </article>
      </section>
    </section>
    <section v-show="activeSection === 'ai'" class="panel">
      <div class="panel-title">
        <div>
          <h2>AI 处理队列与预算</h2>
          <p v-if="runtime">
            全局自动 AI {{ runtime.crawlAutoAiEnabled ? "已开启" : "已关闭" }} ·
            调度器 {{ runtime.crawlSchedulerEnabled ? "已开启" : "已关闭" }} ·
            Provider {{ runtime.llmProvider }} {{ runtime.externalModel }}
          </p>
          <p v-else>正在读取当前运行能力…</p>
          <small v-if="runtime">
            自动采集每日文章 {{ budgetText(runtime.dailyArticleLimit, "篇") }} ·
            Token {{ budgetText(runtime.dailyTokenLimit, "Token") }}
          </small>
        </div>
        <button class="btn secondary" @click="reconcileQueue">
          重新评估等待任务
        </button>
      </div>
      <table class="data-table">
        <thead><tr><th>材料 / 来源</th><th>状态</th><th>预算说明</th><th>预计恢复</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in aiQueue" :key="item.id">
            <td><b>材料 #{{ item.document_id }}</b><small>{{ item.source_name || "人工导入" }} · 队列 #{{ item.id }}</small></td>
            <td>{{ statusLabel(item.status) }}</td>
            <td>{{ item.reason_summary || (item.status === "WAITING_APPROVAL" ? "自动 AI 未开启，等待人工批准。" : "预算正常") }}<small>预计 {{ (item.estimated_tokens || 0).toLocaleString() }} Token</small></td>
            <td>{{ formatDisplayDateTime(item.estimated_recovery_at || item.available_at) }}</td>
            <td>
              <button v-if="item.status === 'WAITING_APPROVAL'" class="text-action strong" @click="approveQueue(item)">人工批准</button>
              <button v-else-if="item.status === 'WAITING_BUDGET'" class="text-action" disabled>等待预算恢复</button>
              <button v-else-if="item.status === 'FAILED'" class="text-action" @click="retryQueue(item)">重试</button>
              <span v-else>无需操作</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && aiQueue.length === 0" class="empty-state">当前没有待处理的 AI 队列任务。</div>
    </section>
    <section v-show="activeSection === 'jobs'" class="panel">
      <div class="panel-title"><div><h2>采集任务中心</h2><p>统一查看任务计数、错误队列和重试状态。</p></div></div>
      <div class="form-row">
        <label class="field">状态筛选<select v-model="taskStatus" @change="load"><option value="">全部状态</option><option v-for="status in ['PENDING','RUNNING','SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED','DISABLED']" :key="status" :value="status">{{statusLabel(status)}}</option></select></label>
        <label class="field">来源筛选<select v-model="taskSourceId" @change="load"><option :value="undefined">全部来源</option><option v-for="source in registries" :key="source.id" :value="source.id">{{source.source_name}}</option></select></label>
      </div>
      <table class="data-table">
        <thead><tr><th>来源 / 触发</th><th>状态</th><th>计数摘要</th><th>开始 / 结束</th><th>错误</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="job in jobs" :key="job.id">
            <td><b>{{job.source_name}}</b><small>#{{job.id}} · {{job.trigger_type}} · {{job.original_url || '来源批次'}}</small></td>
            <td>{{statusLabel(job.status)}}<small>{{job.processing_stage}}</small></td>
            <td>发现 {{job.discovered_count}} · 新增 {{job.added_count}}<small>重复 {{job.duplicate_count}} · 跳过 {{job.skipped_count}} · 失败 {{job.failed_count}}</small></td>
            <td>{{formatDisplayDateTime(job.started_at)}}<small>结束 {{formatDisplayDateTime(job.finished_at)}}</small></td>
            <td>{{job.last_error||"无"}}</td>
            <td><button class="text-action" @click="openJob(job)">详情</button><button v-if="['PENDING','RUNNING'].includes(job.status)" class="text-action danger" @click="stopJob(job)">取消</button><RouterLink v-else-if="job.document_id" :to="`/documents/${job.document_id}/process`">查看材料</RouterLink></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && jobs.length === 0" class="empty-state">当前筛选条件下暂无采集任务。</div>
      <div v-if="selectedJob" class="source-create">
        <div class="panel-title"><div><h2>任务 #{{selectedJob.id}} 详情</h2><p>{{selectedJob.source_name}} · {{statusLabel(selectedJob.status)}}</p></div><button class="btn secondary" @click="selectedJob=null">关闭</button></div>
        <div class="form-actions"><button v-if="selectedJob.errors?.some(item => item.retryable && !item.resolved_at)" class="btn primary" :disabled="retrying !== null" @click="retryFailures(selectedJob)">整批重试可重试项</button></div>
        <div v-if="selectedJob.result?.imported?.length" class="safe-note">
          本次已加入 {{ selectedJob.result.importedCount }} 篇材料。
          <RouterLink :to="{ path: '/documents', query: { importJobId: selectedJob.id } }">查看本次导入</RouterLink>
        </div>
        <table class="data-table"><thead><tr><th>URL / 阶段</th><th>错误</th><th>重试状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in selectedJob.errors || []" :key="item.id"><td>{{item.failed_url || '无地址'}}<small>{{item.processing_stage}} · {{item.error_code}}</small></td><td>{{item.error_summary}}</td><td>{{item.retryable ? `可重试 ${item.retry_count}/3` : '不可重试'}}<small>{{item.next_retry_at ? formatDisplayDateTime(item.next_retry_at) : ''}}</small></td><td><button v-if="item.retryable && !item.resolved_at && item.retry_count < 3" class="text-action" :disabled="retrying !== null" @click="retryError(item.id)">单条重试</button><span v-else>无需操作</span></td></tr></tbody></table>
        <div v-if="!selectedJob.errors?.length" class="empty-state">此任务没有单条错误记录。</div>
      </div>
    </section>
    </div>
  </div>
</template>

<style scoped>
.source-operation-state {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin: 0 0 18px;
  padding: 16px 18px;
  border: 1px solid #c9ddd8;
  border-radius: 10px;
  background: #f2f8f6;
}
.source-operation-state b { color: #174f45; }
.source-operation-state p { margin: 6px 0; line-height: 1.6; }
.source-operation-state small { color: #53645f; line-height: 1.6; }
.source-operation-state.failed { border-color: #e9c8bc; background: #fff7f3; }
.source-operation-state.failed b { color: #8a321f; }
.source-operation-state details { margin-top: 9px; font-size: 12px; }
.source-operation-state code { display: inline-block; margin-top: 5px; }
.source-operation-actions { display: flex; flex: 0 0 auto; gap: 8px; }
.spin { animation: spin 1.2s linear infinite; color: var(--color-primary); flex: 0 0 auto; }
.source-overview {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.source-card {
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: #fff;
}

.source-card header,
.source-card footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.source-card h2,
.source-card p {
  margin: 0;
}

.source-card header p {
  margin-top: 5px;
  color: var(--color-muted);
  font-size: 12px;
}

.source-card header > span {
  padding: 5px 9px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 750;
}

.source-card header > span.ok { background: #e8f3eb; color: #337647; }
.source-card header > span.warning { background: #fff1df; color: #9a5716; }
.source-card header > span.off { background: #edf1f0; color: #66736f; }

.source-card dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0 12px;
}

.source-card dt { color: var(--color-muted); font-size: 11px; }
.source-card dd { margin: 5px 0 0; font-size: 13px; font-weight: 700; }
.source-health-note { color: #52635f; font-size: 12px; }
.source-card footer { margin-top: 18px; padding-top: 16px; border-top: 1px solid #edf1ef; }
.source-card footer svg { width: 16px; }
.source-switch { display: inline-flex; align-items: center; gap: 7px; margin-left: auto; font-size: 12px; font-weight: 700; }
.source-switch input { width: 18px; height: 18px; }
.advanced-toggle { width: 100%; min-height: 54px; margin-bottom: 18px; padding: 0 18px; display: flex; align-items: center; gap: 10px; border: 1px solid var(--color-border); border-radius: 9px; background: #f8faf9; color: #31524d; cursor: pointer; font-weight: 700; }
.advanced-toggle span { margin-left: auto; color: var(--color-muted); font-size: 12px; font-weight: 400; }
.collection-advanced { padding-top: 2px; }

.source-tabs {
  display: flex;
  gap: 8px;
  margin: 0 0 18px;
  padding: 6px;
  overflow-x: auto;
  border: 1px solid var(--border-color, #d9e0e7);
  border-radius: 10px;
  background: #fff;
}

.source-tabs button {
  min-height: 44px;
  padding: 0 16px;
  white-space: nowrap;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #435162;
  font-weight: 650;
  cursor: pointer;
}

.source-tabs button.active {
  background: #eaf3ef;
  color: #185b45;
}

.quick-url-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  align-items: end;
}

.identity-review,
.scan-settings,
.advanced-settings,
.backfill-panel {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid #d9e0e7;
  border-radius: 10px;
  background: #f8fafb;
}

.identity-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.identity-summary div {
  min-width: 0;
  padding: 12px;
  background: #fff;
  border-radius: 8px;
}

.identity-summary span,
.identity-summary b {
  display: block;
}

.identity-summary span {
  margin-bottom: 5px;
  color: #687789;
  font-size: 13px;
}

.identity-summary b {
  overflow-wrap: anywhere;
}

.check-line {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  margin: 12px 0;
}

.check-line input {
  width: 18px;
  height: 18px;
  margin-top: 2px;
}

.advanced-settings legend {
  padding: 0 8px;
  font-weight: 750;
}

.scan-action {
  margin-top: 7px;
}

.batch-actions {
  justify-content: space-between;
}

.runtime-strip { margin: 16px 0; padding: 18px; border: 1px solid #d8e2df; background: #fff; }
.runtime-strip header { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 14px; }
.runtime-strip header div { display: flex; gap: 10px; align-items: baseline; }
.runtime-strip header span { color: #687789; font-size: 13px; }
.runtime-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; }
.runtime-grid article { min-width: 0; padding: 12px; border-left: 3px solid #9aa8a5; background: #f7f9f8; }
.runtime-grid article > * { display: block; overflow-wrap: anywhere; }
.runtime-grid article span, .runtime-grid article small { color: #687789; font-size: 12px; }
.runtime-grid article b { margin: 4px 0; color: #254840; }
.runtime-grid .runtime-ready { border-left-color: #2d7d5f; background: #f2f8f5; }
.runtime-grid .runtime-degraded { border-left-color: #c78322; background: #fff8ec; }
.runtime-grid .runtime-unreachable { border-left-color: #b44949; background: #fff3f3; }

@media (max-width: 720px) {
  .runtime-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .runtime-strip header, .runtime-strip header div { align-items: flex-start; flex-direction: column; }
  .source-operation-state { flex-direction: column; }
  .source-operation-actions { width: 100%; flex-wrap: wrap; }
  .source-overview { grid-template-columns: 1fr; }
  .source-card dl { grid-template-columns: 1fr; }
  .source-card footer { align-items: flex-start; flex-wrap: wrap; }
  .source-switch { margin-left: 0; }
  .advanced-toggle span { display: none; }
  .quick-url-row,
  .identity-summary {
    grid-template-columns: 1fr;
  }

  .source-tabs {
    margin-inline: -4px;
  }
}
</style>
