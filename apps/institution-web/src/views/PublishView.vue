<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave, useRoute } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import {
  documentApi,
  type DocumentDetail,
  type GeneratedContent,
} from "../api/documents";
import { apiMessage } from "../api/http";
import { buildH5GuideUrl } from "../utils/h5-url";
import { CheckCircle2, Send, ShieldCheck } from "lucide-vue-next";

const documentId = Number(useRoute().params.id);
const document = ref<DocumentDetail | null>(null);
const fieldCount = ref(0);
const publishedSlug = ref("");
const error = ref("");
const submitting = ref(false);
const agreed = ref(true);
const initialForm = ref("");
const allowLeave = ref(false);
const form = reactive({
  title: "",
  category: "生活服务",
  summary: "",
  sourceName: "",
  sourceUrl: "",
  publishedAt: new Date().toISOString().slice(0, 10),
  allowPublicOriginal: false,
});
const isDirty = computed(() => Boolean(initialForm.value) && JSON.stringify(form) !== initialForm.value);
onBeforeRouteLeave(() => {
  if (allowLeave.value || publishedSlug.value || !isDirty.value) return true;
  return window.confirm("发布信息尚未保存，确定离开吗？");
});
const h5Url = computed(() =>
  publishedSlug.value
    ? buildH5GuideUrl(publishedSlug.value, {
        configuredBaseUrl: import.meta.env.VITE_H5_BASE_URL,
        isDev: import.meta.env.DEV,
        protocol: window.location.protocol,
        hostname: window.location.hostname,
      }, document.value?.source_type === "WEB_ARTICLE" ? "news" : "guide")
    : "",
);

function parseJson(value?: string): Record<string, string> {
  if (!value) return {};
  try {
    return JSON.parse(value) as Record<string, string>;
  } catch {
    return {};
  }
}

onMounted(async () => {
  try {
    const [detailResponse, generatedResponse, fieldsResponse] =
      await Promise.all([
        documentApi.detail(documentId),
        documentApi.generated(documentId),
        documentApi.fields(documentId),
      ]);
    document.value = detailResponse.data.data;
    const generated = generatedResponse.data.data as GeneratedContent[];
    const summary = generated.find((item) => item.content_type === "SUMMARY");
    const source = parseJson(
      generated.find((item) => item.content_type === "SOURCE_INFO")
        ?.content_json,
    );
    form.title = document.value.title;
    form.category = document.value.category || "生活服务";
    form.summary = summary?.plain_text || "";
    form.sourceName =
      source.source_name || document.value.source_name || document.value.organization_name || "";
    form.sourceUrl = document.value.import_url || source.source_url || "";
    form.publishedAt = String(
      document.value.source_published_at || new Date().toISOString(),
    ).slice(0, 10);
    fieldCount.value = fieldsResponse.data.data.length;
    initialForm.value = JSON.stringify(form);
  } catch (cause) {
    error.value = apiMessage(cause);
  }
});

async function publish() {
  if (!window.confirm("确认审核通过并发布到用户端吗？发布后公众即可查看。")) return;
  submitting.value = true;
  error.value = "";
  try {
    const response = await documentApi.publish(documentId, {
      title: form.title,
      category: form.category,
      sourceName: form.sourceName,
      sourceUrl: form.sourceUrl,
      allowPublicOriginal: form.allowPublicOriginal,
    });
    publishedSlug.value = response.data.data.slug;
    allowLeave.value = true;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="narrow">
    <PageHeader
      title="审核与发布"
      description="确认分类、来源和用户端展示效果后发布。"
      :breadcrumbs="['材料管理', '审核与发布']"
      status="待发布"
    />
    <div v-if="publishedSlug" class="success-panel">
      <CheckCircle2 />
      <h2>内容已成功发布</h2>
      <p>“{{ form.title }}”已出现在用户端公开内容中。</p>
      <div>
        <RouterLink class="btn primary" to="/published"
          >查看已发布内容</RouterLink
        >
        <a class="btn secondary" :href="h5Url" target="_blank" rel="noreferrer"
          >打开用户端</a
        >
      </div>
    </div>
    <section v-else class="panel publish-form">
      <div class="review-ok">
        <ShieldCheck />
        <span
          ><b>关键字段审核已完成</b
          ><small
            >{{ fieldCount }} 个字段已确认，审核记录将随本次发布保存。</small
          ></span
        >
      </div>
      <div class="form-row">
        <label class="field"
          >发布标题<input v-model="form.title" required
        /></label>
        <label class="field">
          内容分类
          <select v-model="form.category">
            <option>养老</option>
            <option>健康</option>
            <option>反诈</option>
            <option>生活服务</option>
            <option>时政</option>
            <option>养老政策</option>
            <option>社区服务</option>
            <option>文化学习</option>
            <option>办事通知</option>
          </select>
        </label>
      </div>
      <label class="field"
        >摘要<textarea v-model="form.summary" rows="3" readonly></textarea>
      </label>
      <div class="form-row">
        <label class="field"
          >来源名称<input v-model="form.sourceName" required
        /></label>
        <label class="field"
          >公开日期<input v-model="form.publishedAt" type="date" readonly
        /></label>
      </div>
      <label class="field"
        >来源 URL<input v-model="form.sourceUrl" type="url"
      /></label>
      <label v-if="document?.source_type !== 'WEB_ARTICLE'" class="check"
        ><input
          v-model="agreed"
          type="checkbox"
        />我已确认内容准确、来源有效，并同意在用户端公开。</label
      >
      <label class="check"
        ><input v-model="form.allowPublicOriginal" type="checkbox" />允许用户端查看上传的原始{{ document?.mime_type?.startsWith("image/") ? "图片" : "PDF" }}。原文件可能包含个人信息，请确认适合公开。</label
      >
      <p v-if="error" class="form-error">{{ error }}</p>
      <div class="form-actions">
        <button
          class="btn primary"
          :disabled="submitting || !agreed || !form.title || !form.sourceName"
          @click="publish"
        >
          <Send :size="17" />{{ submitting ? "正在发布…" : "审核通过并发布" }}
        </button>
      </div>
    </section>
  </div>
</template>
