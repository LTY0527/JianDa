<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import {
  documentApi,
  type DocumentDetail,
  type ProcessingJob,
} from "../api/documents";
import { apiMessage } from "../api/http";
import {
  CircleCheck,
  LoaderCircle,
  WandSparkles,
  ListChecks,
  ArrowRight,
  RefreshCw,
  TriangleAlert,
  Clock4,
  Workflow,
  Timer,
  Sparkles,
  Gauge,
  ShieldCheck,
  Network,
} from "lucide-vue-next";
import { authorityLevelLabel, contentKindLabel } from "../utils/display";
const route = useRoute();
const documentId = Number(route.params.id);
const requestedJobId = Number(route.query.jobId || 0);
const fields = ref<any[]>([]);
const steps = ref<[string, string][]>([]);
const summary = ref<string[]>([]);
const generated = ref<any[]>([]);
const document = ref<DocumentDetail | null>(null);
const segmentCount = ref(0);
const jobs = ref<ProcessingJob[]>([]);
const error = ref("");
const loading = ref(true);
const refreshing = ref(false);
const retrying = ref(false);
const lastUpdatedAt = ref<Date | null>(null);
const elapsedSeconds = ref(0);
const snapshotQueuePosition = ref(0);
const snapshotActiveProcessing = ref(0);
const snapshotEstimatedMs = ref<string | null>(null);
const startingAI = ref(false);
let pollTimer: ReturnType<typeof setInterval> | null = null;
let elapsedTimer: ReturnType<typeof setInterval> | null = null;
const latestJob = computed(
  () =>
    (requestedJobId
      ? jobs.value.find((job) => job.id === requestedJobId)
      : undefined) || jobs.value[0],
);
const terminalStatuses = new Set([
  "WAITING_REVIEW",
  "FAILED",
  "FAILED_RETRYABLE",
  "WAITING_BUDGET",
  "WAITING_APPROVAL",
  "CANCELLED",
  "PUBLISHED",
  "SUCCEEDED",
]);
const terminal = computed(
  () =>
    terminalStatuses.has(document.value?.processing_status || "") ||
    terminalStatuses.has(latestJob.value?.status || "") ||
    latestJob.value?.status === "SUCCEEDED",
);
const hasReviewContent = computed(
  () => fields.value.length > 0 || generated.value.length > 0,
);
const deterministicFallback = computed(() =>
  generated.value.find((item) => item.content_type === "REWRITE_STATUS"),
);
const completedStatuses = new Set([
  "WAITING_REVIEW",
  "REVIEWED",
  "PUBLISHED",
  "WITHDRAWN",
]);
const structuredModuleTypes = new Set([
  "DOCUMENT_OUTLINE",
  "SECTION_SUMMARIES",
  "STANDARD_SECTIONS",
  "POLICY_SECTIONS",
  "HEALTH_GUIDANCE",
  "ACTION_CHECKLIST",
  "KEY_FACTS",
  "RISK_WARNING",
]);
const structuredModules = computed(() =>
  generated.value
    .filter((item) => structuredModuleTypes.has(item.content_type))
    .map((item) => ({
      ...item,
      items: moduleItems(item.content_json, item.plain_text),
    }))
    .filter((item) => item.items.length),
);
const completed = computed(
  () =>
    completedStatuses.has(document.value?.processing_status || "") &&
    hasReviewContent.value,
);
const rewriteRecoverable = computed(
  () =>
    failed.value &&
    latestJob.value?.last_failed_stage === "accessible_rewrite" &&
    Boolean(latestJob.value?.fact_checkpoint_json),
);
const isWebArticle = computed(() => document.value?.source_type === "WEB_ARTICLE");
const textLength = computed(() => (document.value?.raw_text || "").length);
const imageCount = computed(() =>
  (document.value?.original_html?.match(/<img\b/gi) || []).length,
);
const extractionMethodText = computed(() => ({
  pymupdf: "PDF 文本层提取",
  ocr: "扫描页本地 OCR 识别",
  "pymupdf+ocr": "文本层提取 + 扫描页 OCR",
  manual: "人工录入正文",
  manual_required: "等待人工录入",
  unknown: "正文提取方式未记录",
}[document.value?.extraction_method || "unknown"]));
const stageText: Record<string, string> = {
  EXTRACTING_TEXT: "正在提取正文",
  DETECTING_DOCUMENT_KIND: "正在识别材料类型",
  EXTRACTING_FACTS: "正在分析材料关键事实",
  ANALYZING_SECTIONS: "正在分析材料章节",
  MERGING_FACTS: "正在合并关键事实",
  VALIDATING_TRACE: "正在校验原文追溯",
  GENERATING_ACCESSIBLE_CONTENT: "正在生成通俗内容",
  SAVING_RESULT: "正在保存结果",
  WAITING_BUDGET: "等待 AI 预算恢复",
  WAITING_APPROVAL: "等待人工批准 AI",
  PREPARING: "正在准备材料与安全校验",
  CANCELLED: "任务已取消",
  QUEUE_REJECTED: "后台处理队列暂时已满",
  REWRITE_PENDING: "事实提取已保留，等待适老化改写",
  accessible_rewrite: "适老化改写失败，可单独重试",
  HEARTBEAT_STALE: "任务心跳超时，可安全重试",
  QUEUED: "已加入处理队列",
  QUEUE_TIMEOUT_STALE: "在队列中等待过久，已安全暂停，可重新提交",
  SUCCEEDED: "处理完成",
  FAILED: "处理失败",
};
const reasonCodeText: Record<string, string> = {
  AUTO_AI_DISABLED: "平台自动 AI 开关关闭，等待人工批准",
  AI_UNAVAILABLE: "AI 服务暂时不可用",
  AI_SERVICE_FAILED: "AI 服务调用失败，通常为网络或模型错误",
  AI_TIMEOUT: "AI 调用超时，通常为 DeepSeek 或 Tavily 响应慢",
  LLM_JSON_PARSE_FAILED: "AI 返回格式无法解析，已记录原始响应指纹",
  DETERMINISTIC_FALLBACK: "已生成基础易读版本，自然化表达失败",
  QUEUE_TIMEOUT_STALE: "在处理队列中等待过久",
  MANUAL_APPROVAL: "平台管理员批准",
  RECONCILED: "管理员重新排队",
  MANUAL_RETRY: "管理员重试",
};

const railStages: Array<{ key: string; label: string; hint: string }> = [
  { key: "SOURCE", label: "材料采集", hint: "" },
  { key: "EXTRACT", label: "正文提取", hint: "" },
  { key: "ANALYZE", label: "AI 分析与改写", hint: "" },
  { key: "REVIEW", label: "等待审核", hint: "" },
];

const estimatedSeconds = computed<number | null>(() => {
  if (snapshotEstimatedMs.value == null) return null;
  const ms = Number(snapshotEstimatedMs.value);
  if (!Number.isFinite(ms) || ms <= 0) return null;
  return Math.round(ms / 1000);
});

const etaText = computed(() => {
  const secs = estimatedSeconds.value;
  if (secs == null) return "估算中…";
  if (secs < 60) return `约 ${secs} 秒`;
  if (secs < 3600) {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return s < 10 ? `约 ${m} 分钟` : `约 ${m} 分 ${s} 秒`;
  }
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return `约 ${h} 小时 ${m} 分`;
});

const notStarted = computed(
  () =>
    !loading.value &&
    !failed.value &&
    !completed.value &&
    (document.value?.processing_status === "UPLOADED" ||
      document.value?.processing_status === "IMPORTED" ||
      !document.value?.processing_status) &&
    jobs.value.length === 0,
);
const retryable = computed(
  () =>
    document.value?.processing_status === "FAILED_RETRYABLE" ||
    latestJob.value?.status === "FAILED_RETRYABLE" ||
    latestJob.value?.stage === "HEARTBEAT_STALE",
);
const failed = computed(
  () =>
    document.value?.processing_status === "FAILED" ||
    document.value?.processing_status === "FAILED_RETRYABLE" ||
    latestJob.value?.status === "FAILED" ||
    latestJob.value?.status === "FAILED_RETRYABLE",
);
const emptyReviewResult = computed(
  () =>
    !loading.value &&
    document.value?.processing_status === "WAITING_REVIEW" &&
    !hasReviewContent.value,
);
const failureMessage = computed(() => {
  const failedJob = jobs.value.find(
    (job) => job.status === "FAILED" || job.status === "FAILED_RETRYABLE",
  );
  const baseError = failedJob?.error_message;
  const reasonCode = (failedJob?.reason_code || latestJob.value?.reason_code || "") as string;
  const reasonHuman = reasonCodeText[reasonCode] || "";
  const stage = (failedJob?.stage || latestJob.value?.stage || "") as string;
  const stageHuman = stageText[stage] || "";
  if (baseError) {
    return baseError + (reasonHuman ? ` · ${reasonHuman}` : "");
  }
  if (stageHuman) return stageHuman + (reasonHuman ? ` · ${reasonHuman}` : "");
  if (reasonHuman) return reasonHuman;
  if (emptyReviewResult.value) return "本次处理未生成可审核字段，请重新处理或查看任务日志";
  return error.value || "处理未完成，请重新尝试";
});

function parseJsonArray(value?: string): unknown[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function moduleItems(contentJson?: string, plainText?: string): string[] {
  if (!contentJson) return plainText ? [plainText] : [];
  try {
    const parsed = JSON.parse(contentJson);
    const values = Array.isArray(parsed)
      ? parsed
      : parsed && typeof parsed === "object"
        ? Object.entries(parsed).map(([label, value]) => ({ label, value }))
        : [parsed];
    return values
      .map((item) => {
        if (typeof item === "string") return item;
        if (!item || typeof item !== "object") return String(item ?? "");
        const record = item as Record<string, unknown>;
        const label = String(record.label || record.title || "");
        const value = String(
          record.value || record.summary || record.description || "",
        );
        return [label, value].filter(Boolean).join("：");
      })
      .filter(Boolean);
  } catch {
    return plainText ? [plainText] : [];
  }
}

function railStageStatus(stageKey: string): "done" | "active" | "pending" | "failed" {
  if (failed.value && (stageKey === "ANALYZE" || stageKey === "REVIEW")) {
    if (stageKey === "REVIEW") return hasReviewContent.value ? "active" : "failed";
    if (stageKey === "ANALYZE") return hasReviewContent.value ? "done" : "failed";
  }
  if (hasReviewContent.value && completed.value) return "done";
  switch (stageKey) {
    case "SOURCE":
      return "done";
    case "EXTRACT":
      return textLength.value > 0 || segmentCount.value > 0 ? "done" : "active";
    case "ANALYZE":
      if (hasReviewContent.value) return "done";
      if (failed.value || emptyReviewResult.value) return "failed";
      return "active";
    case "REVIEW":
      if (completed.value) return "done";
      return hasReviewContent.value ? "active" : "pending";
    default:
      return "pending";
  }
}

async function load(silent = false) {
  if (refreshing.value) return;
  refreshing.value = true;
  if (!silent) loading.value = true;
  error.value = "";
  try {
    const [
      detailResponse,
      fieldResponse,
      generatedResponse,
      segmentResponse,
      jobResponse,
    ] = await Promise.all([
      documentApi.detail(documentId),
      documentApi.fields(documentId),
      documentApi.generated(documentId),
      documentApi.segments(documentId),
      documentApi.jobs(documentId),
    ]);
    document.value = detailResponse.data.data;
    segmentCount.value = segmentResponse.data.data.length;
    jobs.value = jobResponse.data.data;
    generated.value = generatedResponse.data.data;
    fields.value = fieldResponse.data.data.map((field) => ({
      id: field.id,
      label: field.field_label,
      value: field.field_value,
      page: field.page_no,
      quote: field.source_quote,
      confidence: Number(field.confidence),
    }));
    const stepContent = generatedResponse.data.data.find(
      (item) => item.content_type === "STEP_CARDS",
    )?.content_json;
    steps.value = parseJsonArray(stepContent)
      .filter(
        (step): step is Record<string, unknown> =>
          typeof step === "object" && step !== null,
      )
      .map((step) => [String(step.title || ""), String(step.description || "")]);
    const summaryContent = generatedResponse.data.data.find(
      (item) => item.content_type === "SUMMARY",
    );
    summary.value = parseJsonArray(summaryContent?.content_json).map(String);
    lastUpdatedAt.value = new Date();
    if (terminal.value) stopPolling();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    if (!silent) loading.value = false;
    refreshing.value = false;
  }
}

async function loadSnapshot() {
  if (refreshing.value) return;
  refreshing.value = true;
  let reachedTerminal = false;
  try {
    const response = await documentApi.processingSnapshot(documentId);
    const snapshot = response.data.data;
    if (document.value) document.value.processing_status = snapshot.status;
    const current = latestJob.value;
    const compactJob: ProcessingJob = {
      ...(current || { id: snapshot.jobId || 0, status: snapshot.jobStatus || snapshot.status, progress: 0 }),
      id: snapshot.jobId || current?.id || 0,
      status: snapshot.jobStatus || snapshot.status,
      stage: snapshot.stage,
      progress: snapshot.progress,
      error_message: snapshot.error,
      updated_at: snapshot.heartbeat || snapshot.updatedAt,
      total_ms: snapshot.totalMs,
      provider_id: snapshot.providerId,
      model_id: snapshot.modelId,
      reason_code: snapshot.reasonCode,
      retry_count: snapshot.retryCount,
    };
    jobs.value = [compactJob, ...jobs.value.filter((item) => item.id !== compactJob.id)];
    elapsedSeconds.value = snapshot.elapsed;
    snapshotQueuePosition.value = Number(snapshot.queuePosition || 0);
    snapshotActiveProcessing.value = Number(snapshot.activeProcessing || 0);
    snapshotEstimatedMs.value = snapshot.estimatedMs ?? null;
    lastUpdatedAt.value = new Date();
    reachedTerminal = terminalStatuses.has(snapshot.status)
      || terminalStatuses.has(snapshot.jobStatus || "")
      || snapshot.jobStatus === "FAILED_RETRYABLE";
    error.value = "";
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    refreshing.value = false;
  }
  if (reachedTerminal) {
    stopPolling();
    await load(true);
  }
}

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
}

function startPolling() {
  stopPolling();
  pollTimer = setInterval(() => {
    if (!terminal.value) void loadSnapshot();
    else stopPolling();
  }, 1500);
}

function updateElapsed() {
  const started = latestJob.value?.started_at;
  if (!started) {
    elapsedSeconds.value = 0;
    return;
  }
  if (!latestJob.value?.finished_at) {
    // The polling snapshot calculates elapsed time inside MySQL so JVM/DB
    // timezone differences cannot inflate a fresh task by several hours.
    elapsedSeconds.value += 1;
    return;
  }
  const end = new Date(latestJob.value.finished_at).getTime();
  elapsedSeconds.value = Math.max(0, Math.floor((end - new Date(started).getTime()) / 1000));
}

async function startNow() {
  if (startingAI.value) return;
  startingAI.value = true;
  error.value = "";
  try {
    await documentApi.process(documentId);
    startPolling();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    await load();
    startingAI.value = false;
  }
}

async function retry() {
  retrying.value = true;
  error.value = "";
  try {
    if (rewriteRecoverable.value) await documentApi.retryRewrite(documentId);
    else await documentApi.process(documentId);
    startPolling();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    await load();
    retrying.value = false;
  }
}

onMounted(async () => {
  await load();
  updateElapsed();
  if (route.query.autostart === "1" && notStarted.value) {
    void startNow();
  } else if (!terminal.value && !notStarted.value) {
    startPolling();
  }
  elapsedTimer = setInterval(updateElapsed, 1000);
});
onUnmounted(() => {
  stopPolling();
  if (elapsedTimer) clearInterval(elapsedTimer);
});
</script>
<template>
  <div class="processing-page">
    <PageHeader
      title="材料处理中心"
      description="实时查看材料采集、正文提取、AI 分析与审核准备进展。"
      :breadcrumbs="['材料管理', '处理中心']"
      :status="failed ? '处理失败' : completed ? '处理完成' : '处理中'"
    >
      <RouterLink
        v-if="completed"
        class="btn primary"
        :to="`/documents/${documentId}/review`"
      >进入原文对照审核<ArrowRight :size="17" /></RouterLink>
    </PageHeader>

    <section v-if="notStarted" class="panel process-not-started">
      <WandSparkles />
      <div>
        <h2>该材料尚未开始 AI 处理</h2>
        <p>文件已保存，点击下方按钮启动正文提取与 AI 分析。处理过程中可随时离开此页面，后台会继续运行。</p>
        <p v-if="isWebArticle && document" class="info-note">
          来源：{{ document.source_url || document.source_name || "官方网页" }}
          <template v-if="document.source_registry_id"> · 登记来源 #{{ document.source_registry_id }}</template>
        </p>
        <div>
          <RouterLink class="btn secondary" to="/documents">返回材料列表</RouterLink>
          <button class="btn primary" :disabled="startingAI" @click="startNow">
            <WandSparkles v-if="!startingAI" :size="17" />
            <LoaderCircle v-else class="spin" :size="17" />
            {{ startingAI ? "正在启动 AI 处理…" : "开始 AI 处理" }}
          </button>
        </div>
      </div>
    </section>

    <section v-else-if="latestJob" class="process-summary-card">
      <div class="process-summary-card__title">
        <h1>{{ document?.title || `文档 #${documentId}` }}</h1>
        <span v-if="latestJob?.status === 'WAITING_BUDGET'" class="pill pill--warning">等待预算恢复</span>
        <span v-else-if="retryable" class="pill pill--warning">可安全重试</span>
        <span v-else-if="failed" class="pill pill--danger">处理失败</span>
        <span v-else-if="completed" class="pill pill--success">可审核</span>
        <span v-else class="pill pill--primary">处理中</span>
      </div>

      <div class="process-metrics">
        <div class="process-metric">
          <Workflow :size="18" />
          <div>
            <small>当前阶段</small>
            <b>{{ stageText[latestJob?.stage || ""] || "正在处理" }}</b>
          </div>
        </div>
        <div class="process-metric">
          <Gauge :size="18" />
          <div>
            <small>处理进度</small>
            <b>{{ latestJob?.progress || 0 }}%</b>
          </div>
        </div>
        <div class="process-metric">
          <Timer :size="18" />
          <div>
            <small>已耗时</small>
            <b>{{ elapsedSeconds }} 秒</b>
          </div>
        </div>
        <div class="process-metric">
          <Clock4 :size="18" />
          <div>
            <small>预计剩余</small>
            <b>{{ etaText }}</b>
          </div>
        </div>
        <div class="process-metric">
          <Network :size="18" />
          <div>
            <small>队列位置</small>
            <b>{{ snapshotQueuePosition > 0 ? `前方 ${snapshotQueuePosition} 项` : "运行中" }}</b>
          </div>
        </div>
        <div class="process-metric">
          <Sparkles :size="18" />
          <div>
            <small>并行处理</small>
            <b>{{ snapshotActiveProcessing || 0 }} 项</b>
          </div>
        </div>
      </div>

      <div class="process-progress">
        <div class="process-progress__bar"><i :style="{ width: `${Math.min(100, latestJob?.progress || 0)}%` }" /></div>
        <div class="process-progress__meta">
          <span v-if="lastUpdatedAt">最近更新：{{ lastUpdatedAt.toLocaleTimeString("zh-CN", { hour12: false }) }}</span>
          <span v-if="latestJob?.cache_hit">· 已复用相同文件的验证结果</span>
          <span v-if="latestJob?.total_tokens">· {{ latestJob.total_tokens }} Token</span>
          <span v-if="latestJob?.provider_request_id">· 请求编号 {{ latestJob.provider_request_id }}</span>
        </div>
      </div>
    </section>

    <template v-if="latestJob && !notStarted">
      <div class="process-actions">
        <RouterLink class="btn secondary" to="/documents">返回材料列表，后台继续处理</RouterLink>
        <button class="btn secondary" :disabled="refreshing" @click="load(true)">
          <RefreshCw :size="17" />{{ refreshing ? "正在刷新…" : "重新加载状态" }}
        </button>
      </div>

    <section class="process-rail">
      <template v-for="(stage, idx) in railStages" :key="stage.key">
        <div :class="['rail-node', `rail-node--${railStageStatus(stage.key)}`]">
          <div class="rail-node__indicator">
            <CircleCheck v-if="railStageStatus(stage.key) === 'done'" />
            <TriangleAlert v-else-if="railStageStatus(stage.key) === 'failed'" />
            <LoaderCircle v-else />
          </div>
          <div class="rail-node__body">
            <b>{{ stage.label }}</b>
            <small>
              <template v-if="stage.key === 'SOURCE'">{{ isWebArticle ? "官方网页正文快照已保存" : "原始文件已保存" }}</template>
              <template v-else-if="stage.key === 'EXTRACT'">{{
                isWebArticle
                  ? `${textLength} 个字符 · ${segmentCount} 个段落 · ${imageCount} 张图片`
                  : `${document?.page_count || 0} 页 · ${segmentCount} 段 · ${extractionMethodText}`
              }}</template>
              <template v-else-if="stage.key === 'ANALYZE'">{{
                hasReviewContent
                  ? `${contentKindLabel(document?.content_kind)} · ${fields.length} 字段 · ${generated.length} 模块`
                  : failed || emptyReviewResult
                    ? "未生成可审核字段"
                    : "AI 正在识别事实并生成通俗版"
              }}</template>
              <template v-else-if="stage.key === 'REVIEW'">{{ completed ? "可进入原文对照审核" : "尚未进入审核" }}</template>
            </small>
          </div>
        </div>
        <i v-if="idx < railStages.length - 1" :class="['rail-connector', `rail-connector--${railStageStatus(stage.key)}`]" />
      </template>
    </section>

    <p v-if="deterministicFallback" class="inline-warning rewrite-fallback-note">
      <TriangleAlert :size="18" />
      <span><b>已生成基础易读版本</b><br />AI 自然化表达暂未成功，可稍后重新优化；当前内容可继续人工审核。</span>
    </p>
    <p v-if="route.query.imported === 'web'" class="inline-success">
      网页文章已导入为文档 {{ documentId }}，预览阶段未创建其他材料。
    </p>

    <section v-if="isWebArticle && document" class="panel web-process-facts">
      <div><small>来源等级</small><b>{{ authorityLevelLabel(document.source_authority_level) }}</b></div>
      <div><small>内容类型</small><b>{{ contentKindLabel(document.content_kind) }}</b></div>
      <div><small>正文字符</small><b>{{ textLength }}</b></div>
      <div><small>段落数量</small><b>{{ segmentCount }}</b></div>
      <div><small>正文图片</small><b>{{ imageCount }}</b></div>
      <div><small>处理耗时</small><b>{{ latestJob?.total_ms ? `${(latestJob.total_ms / 1000).toFixed(1)} 秒` : "—" }}</b></div>
      <div><small>模型</small><b>{{ latestJob?.model_id || "默认" }}</b></div>
      <div><small>Provider</small><b>{{ latestJob?.provider_id || "默认" }}</b></div>
    </section>

    <section
      v-if="!loading && !notStarted && (failed || emptyReviewResult || error)"
      :class="['panel', retryable ? 'process-retryable' : 'process-failure']"
    >
      <TriangleAlert v-if="!retryable" />
      <RefreshCw v-else class="spin-slow" />
      <div>
        <h2 v-if="retryable">
          {{ rewriteRecoverable
            ? "事实提取已完成，适老化改写可重试"
            : latestJob?.stage === "HEARTBEAT_STALE" || latestJob?.status === "FAILED_RETRYABLE"
              ? "处理中间状态可安全恢复，已自动保留上下文"
              : "处理可重试，不会重复扣费" }}
        </h2>
        <h2 v-else>{{ rewriteRecoverable ? "事实提取已完成，适老化改写失败" : "本次处理没有生成可审核结果" }}</h2>
        <p>{{ failureMessage }}</p>
        <p v-if="latestJob" class="info-note">
          <span v-if="rewriteRecoverable">
            已保留 {{ fields.length }} 个可追溯事实字段，不会再次调用事实提取。
          </span>
          <span v-else-if="retryable && latestJob?.stage === 'HEARTBEAT_STALE'">
            原因：任务超过 10 分钟未收到心跳，系统已安全标记。点击重试可从断点继续。
          </span>
          <span v-if="latestJob.provider_request_id">请求编号：{{ latestJob.provider_request_id }}</span>
          <span v-if="latestJob.reason_code">原因代码：{{ latestJob.reason_code }}</span>
          <span v-if="latestJob.provider_id">Provider：{{ latestJob.provider_id }}</span>
          <span v-if="latestJob.model_id">模型：{{ latestJob.model_id }}</span>
          <span v-if="latestJob.response_fingerprint">响应指纹：{{ latestJob.response_fingerprint }}</span>
          <span>已跨过真实模型调用边界：{{ latestJob.crossed_provider_boundary ? "是" : "否" }}</span>
          <span>重试次数：{{ latestJob.retry_count || 0 }}</span>
        </p>
        <div>
          <RouterLink class="btn secondary" to="/documents"
            >返回材料列表</RouterLink
          >
          <button :class="retryable ? 'btn primary' : 'btn primary'" :disabled="retrying" @click="retry">
            <RefreshCw :size="17" />{{
              retrying
                ? rewriteRecoverable ? "正在重新生成适老化内容…" : "正在重新处理…"
                : retryable
                  ? (rewriteRecoverable ? "重新生成适老化内容" : "安全重试")
                  : (rewriteRecoverable ? "重新生成适老化内容" : "重新处理")
            }}
          </button>
        </div>
      </div>
    </section>

    <section v-if="structuredModules.length" class="panel structured-modules">
      <div class="panel-title">
        <div>
          <h2>材料类型专属结果</h2>
          <p>{{ contentKindLabel(document?.content_kind) }} · 请在审核页逐项核对原文</p>
        </div>
      </div>
      <article v-for="module in structuredModules" :key="module.id">
        <h3>{{ module.title }}</h3>
        <ul>
          <li v-for="item in module.items" :key="item">{{ item }}</li>
        </ul>
      </article>
    </section>

    <div v-if="hasReviewContent" class="result-grid">
      <section class="panel">
        <div class="panel-title">
          <div>
            <h2>结构化字段</h2>
            <p>共识别 {{ fields.length }} 项关键内容</p>
          </div>
          <ShieldCheck :size="22" />
        </div>
        <div class="field-results">
          <article v-for="f in fields" :key="f.id">
            <span
              ><b>{{ f.label }}</b
              ><small
                >第 {{ f.page }} 页 · 可信度
                {{ Math.round(f.confidence * 100) }}%</small
              ></span
            >
            <p>{{ f.value }}</p>
          </article>
        </div>
      </section>
      <aside>
        <section class="panel plain">
          <div class="result-heading">
            <WandSparkles />
            <div>
              <h2>三句话看懂</h2>
              <p>通俗版摘要</p>
            </div>
          </div>
          <ol v-if="summary.length">
            <li v-for="item in summary" :key="item">{{ item }}</li>
          </ol>
          <p v-else class="result-placeholder">暂无可展示的通俗版摘要。</p>
        </section>
        <section class="panel steps-mini">
          <div class="result-heading">
            <ListChecks />
            <div>
              <h2>办理步骤</h2>
              <p>{{ steps.length }} 个清楚步骤</p>
            </div>
          </div>
          <div v-for="(s, i) in steps.slice(0, 3)" :key="s[0]">
            <span>{{ i + 1 }}</span>
            <p>
              <b>{{ s[0] }}</b
              >{{ s[1] }}
            </p>
          </div>
        </section>
      </aside>
    </div>
    </template>
  </div>
</template>

<style scoped>
.processing-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px 24px 64px;
  color: #172326;
}
.process-summary-card {
  background: linear-gradient(135deg, #0E5A55 0%, #146F68 100%);
  color: #fff;
  border-radius: 18px;
  padding: 28px 30px;
  box-shadow: 0 20px 50px rgba(14, 90, 85, .22);
  margin-top: 18px;
}
.process-summary-card__title {
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
}
.process-summary-card__title h1 {
  margin: 0;
  font-size: 26px;
  letter-spacing: .02em;
}
.pill {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
}
.pill--primary { background: rgba(247, 244, 238, .18); }
.pill--success { background: rgba(190, 240, 212, .2); }
.pill--warning { background: rgba(246, 211, 143, .22); }
.pill--danger  { background: rgba(250, 223, 216, .22); }

.process-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-top: 22px;
}
.process-metric {
  background: rgba(255, 255, 255, .12);
  border: 1px solid rgba(255, 255, 255, .18);
  border-radius: 14px;
  padding: 14px 16px;
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}
.process-metric svg { width: 20px; height: 20px; color: #D7E7E3; flex: 0 0 auto; }
.process-metric div { min-width: 0; }
.process-metric small { display: block; font-size: 12px; color: #C7D7D3; }
.process-metric b { display: block; font-size: 17px; margin-top: 2px; overflow-wrap: anywhere; }

.process-progress { margin-top: 22px; }
.process-progress__bar {
  width: 100%;
  height: 12px;
  background: rgba(255, 255, 255, .18);
  border-radius: 999px;
  overflow: hidden;
}
.process-progress__bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #F7F4EE, #EAD8A1);
  transition: width .35s ease;
}
.process-progress__meta {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #D3E3DF;
  font-size: 13px;
}
.process-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 22px 0 28px;
}
.process-rail {
  display: flex;
  gap: 0;
  align-items: stretch;
  padding: 20px 0;
  overflow-x: auto;
}
.rail-node {
  flex: 1 1 0;
  min-width: 180px;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 12px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid #E7ECE9;
}
.rail-node__indicator {
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  background: #0E5A55;
}
.rail-node__body b { display: block; font-size: 16px; }
.rail-node__body small { display: block; margin-top: 4px; color: #667378; font-size: 13px; line-height: 1.6; }
.rail-node--done { background: #F4FAF7; }
.rail-node--done .rail-node__indicator { background: #0E5A55; }
.rail-node--failed { background: #FDF3F0; border-color: #F1C8BF; }
.rail-node--failed .rail-node__indicator { background: #B84A42; }
.rail-node--active { background: #FFF9EE; border-color: #EAD8A1; }
.rail-node--active .rail-node__indicator { background: #D58B32; animation: pulse 1.4s ease-in-out infinite; }
@keyframes pulse { 0%,100% { transform: scale(1); } 50% { transform: scale(1.08); } }
.rail-connector {
  flex: 0 0 34px;
  align-self: center;
  height: 2px;
  background: #D9E2DF;
  margin: 0 6px;
  position: relative;
}
.rail-connector--done { background: linear-gradient(90deg, #0E5A55, #53B09E); }
.rail-connector--active { background: linear-gradient(90deg, #D58B32, #F1DDA0); }
.rail-connector--failed { background: #F1C8BF; }

.process-not-started {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  background: linear-gradient(135deg, #F4FAF7 0%, #E8F5F0 100%);
  border: 1px solid #C8E3D7;
  border-radius: 18px;
  padding: 28px 30px;
  margin-top: 18px;
}
.process-not-started > svg {
  width: 40px; height: 40px; flex: 0 0 40px;
  color: #0E5A55; background: #fff; border-radius: 50%; padding: 8px;
}
.process-not-started h2 { margin: 0 0 8px; font-size: 20px; color: #0E5A55; }
.process-not-started p { margin: 0 0 8px; color: #405953; }
.process-not-started .info-note { font-size: 13px; color: #667378; margin-bottom: 16px; }
.process-not-started > div > div:last-child { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 8px; }

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.web-process-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.web-process-facts div {
  background: #F7F9F8;
  border: 1px solid #E7ECE9;
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.web-process-facts small { color: #667378; font-size: 12px; }
.web-process-facts b { font-size: 16px; }
@media(max-width: 960px){
  .process-metrics { grid-template-columns: repeat(3, 1fr); }
  .web-process-facts { grid-template-columns: repeat(2, 1fr); }
}
@media(max-width: 680px){
  .processing-page { padding: 18px 16px 48px; }
  .process-summary-card { padding: 22px 18px; border-radius: 14px; }
  .process-summary-card__title h1 { font-size: 22px; }
  .process-metrics { grid-template-columns: repeat(2, 1fr); }
  .process-metric b { font-size: 15px; }
  .rail-node { min-width: 160px; }
}
.process-retryable{
  display:flex;align-items:flex-start;gap:16px;
  background:linear-gradient(135deg, #FFF9EE 0%, #FFF3D6 100%);
  border:1px solid #EAD8A1;border-radius:18px;padding:24px 28px;margin-top:18px;
}
.process-retryable > svg:first-child{width:40px;height:40px;flex:0 0 40px;color:#D58B32;background:#fff;border-radius:50%;padding:8px}
.process-retryable h2{margin:0 0 8px;font-size:18px;color:#7A5A00}
.process-retryable p{margin:0 0 8px;color:#65501F}
.process-retryable .info-note{font-size:12px;color:#857348;margin-bottom:14px}
.process-retryable > div > div:last-child{display:flex;gap:10px;flex-wrap:wrap;margin-top:8px}
.spin-slow{animation:spin 2.4s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
