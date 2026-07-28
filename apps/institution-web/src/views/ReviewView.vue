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
import {
  Save,
  CheckCircle2,
  FileText,
  RefreshCw,
  TriangleAlert,
} from "lucide-vue-next";

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
const originalUrl = ref("");
const originalError = ref("");
const originalLoading = ref(false);
const serviceSchedule = ref<any>({ service_windows: [], closure_rules: [] });
const conditionalMaterials = ref<any[]>([]);
const structuredFees = ref<any[]>([]);
const resultDelivery = ref<any[]>([]);
const isImage = computed(() => document.value?.mime_type?.startsWith("image/"));
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
    fields.value.length === 0,
);
const failureMessage = computed(
  () =>
    jobs.value.find((job) => job.status === "FAILED")?.error_message ||
    "本次处理未生成可审核字段，请重新处理或查看任务日志",
);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [detailResponse, fieldResponse, jobResponse, generatedResponse] = await Promise.all([
      documentApi.detail(documentId),
      documentApi.fields(documentId),
      documentApi.jobs(documentId),
      documentApi.generated(documentId),
    ]);
    document.value = detailResponse.data.data;
    jobs.value = jobResponse.data.data;
    fields.value = fieldResponse.data.data.map((field) => ({
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
    const generated = generatedResponse.data.data;
    const parsed = (type: string, fallback: any) => {
      const value = generated.find((item) => item.content_type === type)?.content_json;
      if (!value) return fallback;
      try { return JSON.parse(value); } catch { return fallback; }
    };
    serviceSchedule.value = parsed("SERVICE_SCHEDULE", { service_windows: [], closure_rules: [] });
    conditionalMaterials.value = parsed("CONDITIONAL_MATERIALS", []);
    structuredFees.value = parsed("FEES", []);
    resultDelivery.value = parsed("RESULT_DELIVERY", []);
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function showOriginal() {
  sourceMode.value = "file";
  if (originalUrl.value || originalLoading.value) return;
  originalLoading.value = true;
  originalError.value = "";
  try {
    const response = await documentApi.originalFile(documentId);
    originalUrl.value = URL.createObjectURL(response.data);
  } catch (cause) {
    originalError.value = apiMessage(cause);
  } finally {
    originalLoading.value = false;
  }
}

onBeforeUnmount(() => {
  if (originalUrl.value) URL.revokeObjectURL(originalUrl.value);
});

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
</script>

<template>
  <div class="review-page">
    <PageHeader
      title="原文对照审核"
      description="逐项核对 AI 结果与原文依据，确认无误后提交审核。"
      :breadcrumbs="['材料管理', '原文对照审核']"
      :status="emptyReviewResult ? '结果异常' : '待审核'"
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
        :disabled="!fields.length || submitting"
        @click="finish"
      >
        {{ submitting ? "正在提交…" : "完成字段审核" }}
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

    <div v-if="fields.length" class="compare">
      <section class="source-pane">
        <div class="pane-title">
          <FileText :size="18" /><b>{{ isImage ? "材料原图" : "材料原文" }}</b
          ><span>第 {{ fields[active].page }} 页 / 共 {{ pageCount }} 页</span>
        </div>
        <div class="source-tabs" role="tablist" aria-label="材料查看方式">
          <button :class="{ active: sourceMode === 'file' }" @click="showOriginal">
            {{ isImage ? "原图" : "原PDF" }}
          </button>
          <button :class="{ active: sourceMode === 'text' }" @click="sourceMode = 'text'">
            提取文本
          </button>
        </div>
        <div v-if="sourceMode === 'file'" class="original-file-pane">
          <p v-if="originalLoading">正在读取原文件…</p>
          <p v-else-if="originalError" class="form-error">{{ originalError }}</p>
          <img v-else-if="originalUrl && isImage" :src="originalUrl" :alt="document?.original_filename || '材料原图'" />
          <iframe v-else-if="originalUrl" :src="originalUrl" title="原PDF预览"></iframe>
        </div>
        <article v-else class="paper">
          <h2>{{ document?.title }}</h2>
          <p
            v-for="(paragraph, index) in sourceParagraphs"
            :key="index"
            :class="{
              highlight:
                paragraph.includes(fields[active].quote) ||
                fields[active].quote.includes(paragraph),
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
