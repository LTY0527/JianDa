<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
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
} from "lucide-vue-next";
const route = useRoute();
const documentId = Number(route.params.id);
const fields = ref<any[]>([]);
const steps = ref<[string, string][]>([]);
const summary = ref<string[]>([]);
const document = ref<DocumentDetail | null>(null);
const segmentCount = ref(0);
const jobs = ref<ProcessingJob[]>([]);
const error = ref("");
const loading = ref(true);
const retrying = ref(false);
const failed = computed(() => document.value?.processing_status === "FAILED");
const emptyReviewResult = computed(
  () =>
    !loading.value &&
    document.value?.processing_status === "WAITING_REVIEW" &&
    fields.value.length === 0,
);
const failureMessage = computed(
  () =>
    jobs.value.find((job) => job.status === "FAILED")?.error_message ||
    (emptyReviewResult.value
      ? "本次处理未生成可审核字段，请重新处理或查看任务日志"
      : error.value || "处理未完成，请重新尝试"),
);

function parseJsonArray(value?: string): unknown[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

async function load() {
  loading.value = true;
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
</script>
<template>
  <div>
    <PageHeader
      title="AI 处理结果"
      description="查看结构化字段、通俗版摘要和办理步骤。"
      :breadcrumbs="['材料管理', '处理结果']"
      :status="failed ? '处理失败' : fields.length ? '待审核' : '处理中'"
      ><RouterLink
        v-if="fields.length"
        class="btn primary"
        :to="`/documents/${documentId}/review`"
        >进入对照审核<ArrowRight :size="17" /></RouterLink
    ></PageHeader>
    <section class="process-rail">
      <div class="done">
        <CircleCheck /><span><b>材料上传</b><small>原始文件已保存</small></span>
      </div>
      <i></i>
      <div class="done">
        <CircleCheck /><span
          ><b>正文提取</b
          ><small
            >共 {{ document?.page_count || 0 }} 页，{{ segmentCount }} 个段落</small
          ></span
        >
      </div>
      <i></i>
      <div :class="{ done: fields.length, failed }">
        <CircleCheck v-if="fields.length" />
        <TriangleAlert v-else-if="failed || emptyReviewResult" />
        <LoaderCircle v-else /><span
          ><b>AI 分析</b
          ><small>{{
            fields.length
              ? `已生成 ${fields.length} 个可追溯字段`
              : failed || emptyReviewResult
                ? "未生成可审核字段"
                : "正在等待分析结果"
          }}</small></span
        >
      </div>
      <i></i>
      <div :class="{ done: fields.length, active: !fields.length && !failed }">
        <CircleCheck v-if="fields.length" />
        <LoaderCircle v-else /><span
          ><b>等待审核</b
          ><small>{{ fields.length ? "请确认关键字段" : "尚未进入审核" }}</small></span
        >
      </div>
    </section>
    <section
      v-if="!loading && (failed || emptyReviewResult || error)"
      class="panel process-failure"
    >
      <TriangleAlert />
      <div>
        <h2>本次处理没有生成可审核结果</h2>
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
    <div v-if="fields.length" class="result-grid">
      <section class="panel">
        <div class="panel-title">
          <div>
            <h2>结构化字段</h2>
            <p>共识别 {{ fields.length }} 项关键内容</p>
          </div>
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
  </div>
</template>
