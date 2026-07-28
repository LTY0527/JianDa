<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
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
    const [detailResponse, fieldResponse, jobResponse] = await Promise.all([
      documentApi.detail(documentId),
      documentApi.fields(documentId),
      documentApi.jobs(documentId),
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
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
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
          <FileText :size="18" /><b>原始材料</b
          ><span>第 {{ fields[active].page }} 页 / 共 {{ pageCount }} 页</span>
        </div>
        <article class="paper">
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
