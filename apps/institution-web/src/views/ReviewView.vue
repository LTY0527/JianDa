<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import {
  documentApi,
  type DocumentDetail,
  type ProcessingJob,
} from "../api/documents";
import { apiMessage } from "../api/http";
import { publicSourceApi, type ImageCandidate } from "../api/publicSources";
import {
  Save,
  CheckCircle2,
  ExternalLink,
  FileText,
  RefreshCw,
  TriangleAlert,
} from "lucide-vue-next";
import PdfReader from "@jianda/shared-ui/PdfReader.vue";
import ImageReader from "@jianda/shared-ui/ImageReader.vue";
import {
  authorityLevelLabel,
  coverTypeLabel,
  formatDisplayDateTime,
} from "../utils/display";

const route = useRoute();
const router = useRouter();
const documentId = Number(route.params.id);
const document = ref<DocumentDetail | null>(null);
const fields = ref<any[]>([]);
const active = ref(0);
const values = ref<string[]>([]);
const confirmed = ref<number[]>([]);
const error = ref("");
const submitting = ref(false);
const loading = ref(true);
const retrying = ref(false);
const jobs = ref<ProcessingJob[]>([]);
const allowLeave = ref(false);
const sourceMode = ref<"file" | "text">("text");
const originalUrl = computed(() => documentApi.originalFileUrl(documentId));
const originalDownloadUrl = computed(() => documentApi.originalFileUrl(documentId, true));
const originalHeaders = computed(() => documentApi.originalFileHeaders());
const serviceSchedule = ref<any>({ service_windows: [], closure_rules: [] });
const conditionalMaterials = ref<any[]>([]);
const structuredFees = ref<any[]>([]);
const resultDelivery = ref<any[]>([]);
const summaryItems = ref<string[]>([]);
const plainText = ref("");
const coverReviewed = ref(false);
const coverFailed = ref(false);
const resourceWarnings = ref<string[]>([]);
const customCoverPreview = ref("");
const coverPosition = ref(50);
const coverUploading = ref(false);
const imageCandidates = ref<ImageCandidate[]>([]);
const candidateSourceName = ref("");
const candidateUsageBasis = ref("");
const candidateRejectionReason = ref("");
const isImage = computed(() => document.value?.mime_type?.startsWith("image/"));
const isWebArticle = computed(() => document.value?.source_type === "WEB_ARTICLE");
const officialPageAvailable = computed(
  () => document.value?.original_page_available !== false,
);
const defaultCoverUrl = computed(() => {
  const name =
    document.value?.content_kind === "HEALTH_EDUCATION"
      ? "health"
      : document.value?.content_kind === "POLICY_NEWS"
        ? "policy"
        : document.value?.content_kind === "ANTI_FRAUD"
          ? "fraud"
          : document.value?.content_kind === "COMMUNITY_SERVICE"
            ? "community"
            : "service";
  const base = import.meta.env.DEV
    ? "http://127.0.0.1:5174"
    : `${window.location.protocol}//${window.location.hostname}`;
  return new URL(`/images/defaults/${name}.svg`, base).toString();
});
const reviewCoverUrl = computed(() =>
  customCoverPreview.value || (!coverFailed.value && document.value?.cover_image_url
    ? document.value.cover_image_url
    : defaultCoverUrl.value),
);
const canFinish = computed(() => fields.value.length > 0 || (isWebArticle.value && summaryItems.value.length > 0));
const canSubmitReview = computed(
  () => canFinish.value && document.value?.processing_status === "WAITING_REVIEW",
);
const reviewActionLabel = computed(() => {
  if (document.value?.processing_status === "PUBLISHED") return "内容已发布";
  if (document.value?.processing_status === "REVIEWED") return "字段已审核";
  return submitting.value ? "正在提交…" : "完成字段审核";
});
const isDirty = computed(() => values.value.some((value, index) => value !== fields.value[index]?.value));
onBeforeRouteLeave(() => {
  if (allowLeave.value || !isDirty.value) return true;
  return window.confirm("审核内容尚未保存，确定离开吗？");
});
const sourceParagraphs = computed(() =>
  (document.value?.raw_text || "原文暂未录入")
    .split(/\r?\n+/)
    .map((text) => text.trim())
    .filter(Boolean),
);
const pageCount = computed(() => document.value?.page_count || 1);
const emptyReviewResult = computed(
  () =>
    !loading.value &&
    document.value?.processing_status === "WAITING_REVIEW" &&
    !canFinish.value,
);
const reviewStatusLabel = computed(() => {
  if (emptyReviewResult.value) return "结果异常";
  if (document.value?.processing_status === "PUBLISHED") return "已发布";
  if (document.value?.processing_status === "REVIEWED") return "已审核";
  return "待审核";
});
const failureMessage = computed(
  () =>
    jobs.value.find((job) => job.status === "FAILED")?.error_message ||
    "本次处理未生成可审核字段，请重新处理或查看任务日志",
);

async function load() {
  loading.value = true;
  error.value = "";
  resourceWarnings.value = [];
  try {
    const detailResponse = await documentApi.detail(documentId);
    document.value = detailResponse.data.data;
    const [fieldResponse, jobResponse, generatedResponse] = await Promise.allSettled([
      documentApi.fields(documentId),
      documentApi.jobs(documentId),
      documentApi.generated(documentId),
    ]);
    const fieldData = fieldResponse.status === "fulfilled"
      ? fieldResponse.value.data.data : [];
    if (fieldResponse.status === "rejected") {
      resourceWarnings.value.push(`审核字段暂时无法读取：${apiMessage(fieldResponse.reason)}`);
    }
    jobs.value = jobResponse.status === "fulfilled"
      ? jobResponse.value.data.data : [];
    if (jobResponse.status === "rejected") {
      resourceWarnings.value.push(`处理任务暂时无法读取：${apiMessage(jobResponse.reason)}`);
    }
    fields.value = fieldData.map((field) => ({
      id: field.id,
      label: field.field_label,
      value: field.field_value,
      page: field.page_no,
      quote: field.source_quote,
      confidence: Number(field.confidence),
      reviewStatus: field.review_status,
      duplicateSuspected: Boolean(field.duplicate_suspected),
    }));
    values.value = fields.value.map((field) => field.value);
    confirmed.value = fields.value
      .map((field, index) => (field.reviewStatus === "CONFIRMED" ? index : -1))
      .filter((index) => index >= 0);
    const generated = generatedResponse.status === "fulfilled"
      ? generatedResponse.value.data.data : [];
    if (generatedResponse.status === "rejected") {
      resourceWarnings.value.push(`AI 适老化内容暂时无法读取：${apiMessage(generatedResponse.reason)}`);
    }
    const parsed = (type: string, fallback: any) => {
      const value = generated.find((item) => item.content_type === type)?.content_json;
      if (!value) return fallback;
      try { return JSON.parse(value); } catch { return fallback; }
    };
    serviceSchedule.value = parsed("SERVICE_SCHEDULE", { service_windows: [], closure_rules: [] });
    conditionalMaterials.value = parsed("CONDITIONAL_MATERIALS", []);
    structuredFees.value = parsed("FEES", []);
    resultDelivery.value = parsed("RESULT_DELIVERY", []);
    summaryItems.value = parsed("SUMMARY", []);
    plainText.value = generated.find((item) => item.content_type === "PLAIN_TEXT")?.plain_text || "";
    coverReviewed.value = Boolean(document.value.image_reviewed);
    if (isWebArticle.value) {
      const candidateResponse = await publicSourceApi.imageCandidates(documentId);
      imageCandidates.value = candidateResponse.data.data;
    }
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

function showOriginal() {
  sourceMode.value = "file";
}

async function retry() {
  retrying.value = true;
  error.value = "";
  try {
    await documentApi.process(documentId);
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    await load();
    retrying.value = false;
  }
}

onMounted(load);
onBeforeUnmount(() => {
  if (customCoverPreview.value) URL.revokeObjectURL(customCoverPreview.value);
});

async function confirm(index: number) {
  try {
    await documentApi.updateField(
      documentId,
      fields.value[index].id,
      values.value[index],
      true,
    );
    fields.value[index].value = values.value[index];
    if (!confirmed.value.includes(index)) confirmed.value.push(index);
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function saveDraft() {
  error.value = "";
  try {
    for (let index = 0; index < fields.value.length; index += 1) {
      await documentApi.updateField(
        documentId,
        fields.value[index].id,
        values.value[index],
        confirmed.value.includes(index),
      );
      fields.value[index].value = values.value[index];
    }
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function finish() {
  submitting.value = true;
  error.value = "";
  try {
    for (let index = 0; index < fields.value.length; index += 1) {
      await documentApi.updateField(
        documentId,
        fields.value[index].id,
        values.value[index],
        true,
      );
    }
    await documentApi.review(documentId);
    allowLeave.value = true;
    await router.push(`/documents/${documentId}/publish`);
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    submitting.value = false;
  }
}

async function confirmCover() {
  try {
    await publicSourceApi.confirmWebCover(documentId);
    coverReviewed.value = true;
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function useCategoryDefaultCover() {
  try {
    await publicSourceApi.useCategoryDefaultCover(documentId);
    if (document.value) {
      document.value.cover_image_url = "";
      document.value.cover_image_type = "CATEGORY_DEFAULT";
    }
    customCoverPreview.value = "";
    coverReviewed.value = true;
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function uploadCustomCover(event: Event) {
  const input = event.currentTarget as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  coverUploading.value = true;
  error.value = "";
  try {
    await documentApi.uploadCover(documentId, file);
    if (customCoverPreview.value) URL.revokeObjectURL(customCoverPreview.value);
    customCoverPreview.value = URL.createObjectURL(file);
    if (document.value) {
      document.value.cover_image_type = "EDITOR_UPLOAD";
      document.value.image_source_name = "机构编辑上传";
      document.value.image_reviewed = true;
    }
    coverReviewed.value = true;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    coverUploading.value = false;
    input.value = "";
  }
}

async function approveCandidate(candidate: ImageCandidate) {
  error.value = "";
  try {
    await publicSourceApi.approveImageCandidate(candidate.id, candidateSourceName.value, candidateUsageBasis.value);
    if (document.value) {
      document.value.cover_image_url = candidate.candidate_url;
      document.value.cover_image_type = candidate.discovery_method === "ARTICLE_IMAGE" ? "ARTICLE_IMAGE" : "ORIGINAL_COVER";
      document.value.image_source_name = candidateSourceName.value;
      document.value.image_license_note = candidateUsageBasis.value;
      document.value.image_reviewed = true;
    }
    coverReviewed.value = true;
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function rejectCandidate(candidate: ImageCandidate) {
  error.value = "";
  try {
    await publicSourceApi.rejectImageCandidate(candidate.id, candidateRejectionReason.value);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

</script>

<template>
  <div class="review-page">
    <PageHeader
      title="原文对照审核"
      description="逐项核对 AI 结果与原文依据，确认无误后提交审核。"
      :breadcrumbs="['材料管理', '原文对照审核']"
      :status="reviewStatusLabel"
    >
      <button
        class="btn secondary"
        :disabled="!fields.length"
        @click="saveDraft"
      >
        <Save :size="17" />保存草稿
      </button>
      <button
        class="btn primary"
        :disabled="!canSubmitReview || submitting"
        @click="finish"
      >
        {{ reviewActionLabel }}
      </button>
    </PageHeader>
    <div class="review-toolbar">
      <span>{{ document?.title || "正在加载材料…" }}</span>
      <div>
        <b>已确认 {{ confirmed.length }} / {{ fields.length }}</b>
        <div class="review-progress">
          <i
            :style="{
              width: fields.length
                ? (confirmed.length / fields.length) * 100 + '%'
                : '0%',
            }"
          ></i>
        </div>
      </div>
    </div>
    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-for="warning in resourceWarnings" :key="warning" class="inline-error">
      {{ warning }}
    </p>
    <p v-if="isWebArticle && (document?.version_no || 1) > 1" class="version-review-note">
      当前审核对象：V{{ document?.version_no }}。已发布的 V{{ (document?.version_no || 1) - 1 }} 继续保持公开，只有本版本完成人工审核并发布后才会替换。
    </p>

    <section v-if="isWebArticle" class="panel web-source-review">
      <div class="web-source-review__cover">
        <img :src="reviewCoverUrl" :alt="document?.image_alt_text || document?.title || '网页文章分类默认图'" :style="{ objectPosition: `${coverPosition}% center` }" referrerpolicy="no-referrer" @error="coverFailed = true" />
        <label class="cover-crop-control">裁剪预览位置
          <input v-model.number="coverPosition" type="range" min="0" max="100" />
        </label>
      </div>
      <div>
        <h2>网页来源与封面审核</h2>
        <dl><div><dt>权威来源</dt><dd>{{ document?.source_name }} · {{ authorityLevelLabel(document?.source_authority_level) }}</dd></div><div><dt>原始发布时间</dt><dd>{{ formatDisplayDateTime(document?.original_published_at) }}</dd></div><div><dt>封面类型</dt><dd>{{ coverTypeLabel(document?.cover_image_type) }}</dd></div><div><dt>图片来源</dt><dd>{{ document?.image_source_name || "简达本地分类默认图" }}</dd></div><div><dt>是否缓存</dt><dd>{{ document?.image_cached ? "是" : "否" }}</dd></div></dl>
        <details class="technical-info"><summary>技术信息</summary><p>内容类型：{{ document?.content_kind }}；封面类型：{{ document?.cover_image_type }}；来源等级：{{ document?.source_authority_level }}</p></details>
        <p>{{ document?.image_license_note }}</p>
        <div class="web-source-review__actions"><a v-if="officialPageAvailable && document?.canonical_url" class="btn secondary" :href="document.canonical_url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="17"/>查看官方原文</a><span v-else class="source-unavailable"><TriangleAlert :size="16"/>原网页暂时不可访问，不影响内部审核</span><button v-if="document?.cover_image_url" class="btn secondary" :disabled="coverReviewed" @click="confirmCover">{{coverReviewed?"封面已确认":"确认使用当前封面"}}</button><label class="btn secondary cover-upload-button">{{coverUploading?"上传中…":"上传自定义封面"}}<input type="file" accept="image/png,image/jpeg,image/webp" :disabled="coverUploading" @change="uploadCustomCover"/></label><button class="btn secondary" @click="useCategoryDefaultCover">使用分类默认图 / 删除封面</button></div>
      </div>
    </section>
    <section v-if="isWebArticle && imageCandidates.length" class="panel web-article-images">
      <h2>图片候选人工审核</h2>
      <p>候选仅供内部核对，未经来源和许可确认不会成为公开封面。</p>
      <div class="form-row"><label class="field">图片来源<input v-model="candidateSourceName" placeholder="例如：新华网原网页" /></label><label class="field">许可说明<input v-model="candidateUsageBasis" placeholder="填写授权、公开使用依据或人工核对说明" /></label></div>
      <label class="field">拒绝原因<input v-model="candidateRejectionReason" placeholder="例如：版权不明确、尺寸不适合或与正文无关" /></label>
      <div><figure v-for="candidate in imageCandidates" :key="candidate.id"><img :src="candidate.candidate_url" :alt="candidate.alt_text || '网页图片候选'" referrerpolicy="no-referrer"/><figcaption><b>图片 URL</b><a :href="candidate.candidate_url" target="_blank" rel="noopener noreferrer">{{candidate.candidate_url}}</a><br/><b>来源页</b><a :href="candidate.source_page_url" target="_blank" rel="noopener noreferrer">{{candidate.source_page_url}}</a><br/>发现方式：{{candidate.discovery_method}} · {{candidate.width}}×{{candidate.height}} · {{candidate.mime_type || '图片'}}<br/>替代文本：{{candidate.alt_text || '无替代文本'}}<br/>状态：{{candidate.review_status}} / {{candidate.rights_status}}</figcaption><div class="form-actions"><button v-if="candidate.review_status === 'PENDING'" class="btn primary" type="button" :disabled="!candidateSourceName.trim() || !candidateUsageBasis.trim()" @click="approveCandidate(candidate)">确认可用</button><button v-if="candidate.review_status === 'PENDING'" class="btn secondary" type="button" @click="rejectCandidate(candidate)">拒绝</button></div></figure></div>
    </section>
    <section v-if="isWebArticle && !imageCandidates.length" class="panel"><h2>图片候选</h2><p>没有通过安全、尺寸和比例过滤的第三方图片，将使用分类默认图。</p></section>

    <section
      v-if="emptyReviewResult"
      class="panel process-failure review-empty"
    >
      <TriangleAlert />
      <div>
        <h2>本次处理未生成可审核字段</h2>
        <p>{{ failureMessage }}</p>
        <div>
          <RouterLink class="btn secondary" to="/documents"
            >返回材料详情</RouterLink
          >
          <button class="btn primary" :disabled="retrying" @click="retry">
            <RefreshCw :size="17" />{{
              retrying ? "正在重新处理…" : "重新处理"
            }}
          </button>
        </div>
      </div>
    </section>

    <div v-if="canFinish" class="compare">
      <section class="source-pane">
        <div class="pane-title">
          <FileText :size="18" /><b>{{ isWebArticle ? "网页正文" : isImage ? "材料原图" : "材料原文" }}</b
          ><span>{{ isWebArticle ? `正文快照 · ${document?.raw_text?.length || 0} 字` : `第 ${fields[active]?.page || 1} 页 / 共 ${pageCount} 页` }}</span>
        </div>
        <div v-if="!isWebArticle" class="source-tabs" role="tablist" aria-label="材料查看方式">
          <button :class="{ active: sourceMode === 'file' }" @click="showOriginal">
            {{ isImage ? "原图" : "原PDF" }}
          </button>
          <button :class="{ active: sourceMode === 'text' }" @click="sourceMode = 'text'">
            提取文本
          </button>
        </div>
        <div v-if="sourceMode === 'file'" class="original-file-pane">
          <ImageReader
            v-if="isImage"
            :src="originalUrl"
            :download-url="originalDownloadUrl"
            :headers="originalHeaders"
            :filename="document?.original_filename || '材料原图'"
            :alt="document?.title || '材料原图'"
          />
          <PdfReader
            v-else
            :src="originalUrl"
            :download-url="originalDownloadUrl"
            :headers="originalHeaders"
            :filename="document?.original_filename || '材料原文.pdf'"
          />
        </div>
        <article v-else class="paper">
          <h2>{{ document?.title }}</h2>
          <p
            v-for="(paragraph, index) in sourceParagraphs"
            :key="index"
            :class="{
              highlight:
                fields[active] && (
                  paragraph.includes(fields[active].quote) ||
                  fields[active].quote.includes(paragraph)
                ),
            }"
          >
            {{ paragraph }}
          </p>
        </article>
      </section>
      <section class="ai-pane">
        <div class="pane-title">
          <b>AI 结构化结果</b><span>请逐项确认</span>
        </div>
        <section v-if="isWebArticle" class="structured-review web-ai-review">
          <h3>三句话看懂</h3>
          <ol><li v-for="item in summaryItems" :key="item">{{item}}</li></ol>
          <h3>适老化正文</h3>
          <p class="web-ai-review__plain">{{ plainText }}</p>
        </section>
        <section v-if="serviceSchedule.service_windows?.length || conditionalMaterials.length || structuredFees.length || resultDelivery.length" class="structured-review">
          <h3>通用结构化结果</h3>
          <div v-if="serviceSchedule.service_windows?.length">
            <b>分时受理安排</b>
            <table><tbody><tr v-for="(window, index) in serviceSchedule.service_windows" :key="index">
              <td>{{ [...(window.days || []), ...(window.dates || [])].join("、") }}</td>
              <td>{{ (window.time_ranges || []).join("、") }}</td>
              <td>{{ window.unavailable_note || window.location }}</td>
            </tr></tbody></table>
            <p v-for="rule in serviceSchedule.closure_rules" :key="rule.value">{{ rule.value }}</p>
          </div>
          <div v-if="conditionalMaterials.length">
            <b>分人群材料</b>
            <p v-for="group in conditionalMaterials" :key="group.applicable_to"><strong>{{ group.applicable_to }}</strong>：必需 {{ (group.required || []).join("、") || "无" }}；可选 {{ (group.optional || []).join("、") || "无" }}</p>
          </div>
          <div v-if="structuredFees.length"><b>费用与支付</b><p v-for="fee in structuredFees" :key="fee.fee_type">{{ fee.fee_type }}：{{ fee.amount || fee.rule }}；{{ (fee.payment_methods || []).join("、") }}</p></div>
          <div v-if="resultDelivery.length"><b>领取与邮寄</b><p v-for="delivery in resultDelivery" :key="delivery.method">{{ delivery.method }}：{{ [delivery.available_after, delivery.location, delivery.fee_rule].filter(Boolean).join("；") }}</p></div>
        </section>
        <div class="review-fields">
          <article
            v-for="(field, index) in fields"
            :key="field.id"
            :class="{
              active: active === index,
              confirmed: confirmed.includes(index),
            }"
            @click="active = index"
          >
            <header>
              <b>{{ field.label }}</b>
              <span v-if="field.duplicateSuspected" class="duplicate-warning"
                ><TriangleAlert />疑似重复字段</span
              >
              <span v-if="confirmed.includes(index)"
                ><CheckCircle2 />已确认</span
              >
              <span v-else :class="{ risk: field.confidence < 0.93 }">{{
                field.confidence < 0.93 ? "请重点核对" : "待确认"
              }}</span>
            </header>
            <textarea v-model="values[index]" rows="2"></textarea>
            <div class="trace">
              <span>原文依据 · 第 {{ field.page }} 页</span>
              <p>“{{ field.quote }}”</p>
            </div>
            <button class="confirm-btn" @click.stop="confirm(index)">
              <CheckCircle2 :size="17" />确认此字段
            </button>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>
