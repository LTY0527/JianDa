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
import { CheckCircle2, Send, ShieldCheck, Smartphone, Type, HeartPulse, HandHeart,
  Utensils, ClipboardList, ShieldAlert, CalendarDays, UsersRound } from "lucide-vue-next";

const documentId = Number(useRoute().params.id);
const document = ref<DocumentDetail | null>(null);
const fieldCount = ref(0);
const previewFields = ref<Array<{ label: string; value: string }>>([]);
const previewSteps = ref<string[]>([]);
const previewFont = ref<20 | 24>(20);
const publishedSlug = ref("");
const nextReviewDocumentId = ref<number | null>(null);
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
  publishChannel: "COMMUNITY" as "HEALTH" | "ELDERLY" | "MEALS" | "SERVICES" | "FRAUD" | "ACTIVITY" | "COMMUNITY",
  promoteToRecommend: false,
  importanceLevel: "NORMAL" as "NORMAL" | "IMPORTANT" | "URGENT",
});
const channelOptions = [
  { value: "HEALTH", label: "健康", icon: HeartPulse },
  { value: "ELDERLY", label: "养老", icon: HandHeart },
  { value: "MEALS", label: "助餐", icon: Utensils },
  { value: "SERVICES", label: "办事", icon: ClipboardList },
  { value: "FRAUD", label: "防诈", icon: ShieldAlert },
  { value: "ACTIVITY", label: "活动", icon: CalendarDays },
  { value: "COMMUNITY", label: "社区", icon: UsersRound },
] as const;
const selectedChannelLabel = computed(() => channelOptions.find((item) => item.value === form.publishChannel)?.label || "社区");
const isDirty = computed(() => Boolean(initialForm.value) && JSON.stringify(form) !== initialForm.value);
const imageReviewBlocked = computed(
  () =>
    document.value?.source_type === "WEB_ARTICLE" &&
    document.value?.image_reviewed !== true,
);
const regionScopeBlocked = computed(() =>
  !document.value?.local_scope || ["UNSPECIFIED", "UNCLASSIFIED"].includes(document.value.local_scope),
);
const publishedRegionLabel = computed(() => {
  const current = document.value;
  if (!current) return "未设置";
  if (current.local_scope === "LOCAL_TOWN") return [current.district, current.street_or_town].filter(Boolean).join(" · ");
  if (["DISTRICT_SHARED", "DISTRICT"].includes(current.local_scope || "")) return `${current.district || "宝山区"}（全区）`;
  if (["CITY_SHARED", "CITY"].includes(current.local_scope || "")) return current.city || "上海市";
  if (["NATIONAL_SHARED", "NATIONAL", "PROVINCE"].includes(current.local_scope || "")) return "全国";
  return "未设置";
});
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
      }, document.value?.source_type === "WEB_ARTICLE" ? "news" : "guide", document.value?.region_code)
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
function parseArray(value?: string): unknown[] {
  if (!value) return [];
  try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed : []; }
  catch { return []; }
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
    form.publishChannel = document.value.publish_channel || document.value.suggested_publish_channel || "COMMUNITY";
    fieldCount.value = fieldsResponse.data.data.length;
    previewFields.value = fieldsResponse.data.data
      .filter((field) => field.field_value?.trim())
      .slice(0, 6)
      .map((field) => ({ label: field.field_label, value: field.field_value }));
    previewSteps.value = parseArray(generated.find((item) => item.content_type === "STEP_CARDS")?.content_json)
      .slice(0, 3)
      .map((step) => typeof step === "string" ? step : String((step as Record<string, unknown>).title || (step as Record<string, unknown>).description || ""))
      .filter(Boolean);
    initialForm.value = JSON.stringify(form);
  } catch (cause) {
    error.value = apiMessage(cause);
  }
});

async function publish() {
  if (imageReviewBlocked.value) {
    error.value = "网页文章图片尚未完成人工审核，请返回审核页确认候选图片或使用分类默认图。";
    return;
  }
  if (regionScopeBlocked.value) {
    error.value = "发布前必须明确内容适用地区，请返回材料页设置街镇、宝山区、上海市或全国范围。";
    return;
  }
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
      publishChannel: form.publishChannel,
      promoteToRecommend: form.promoteToRecommend,
      importanceLevel: form.importanceLevel,
    });
    publishedSlug.value = response.data.data.slug;
    allowLeave.value = true;
    try {
      const documents = await documentApi.list();
      nextReviewDocumentId.value = documents.data.data.find(
        (item) => item.id !== documentId && ["WAITING_REVIEW", "AI_PROCESSED"].includes(item.status),
      )?.id || null;
    } catch {
      nextReviewDocumentId.value = null;
    }
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="publish-page">
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
      <p><b>已发布到：</b>{{ publishedRegionLabel }}　<b>栏目：</b>{{ selectedChannelLabel }}</p>
      <div>
        <RouterLink class="btn primary" to="/published"
          >查看已发布内容</RouterLink
        >
        <a class="btn secondary" :href="h5Url" target="_blank" rel="noreferrer"
          >打开用户端</a
        >
        <RouterLink class="btn secondary" to="/">返回工作台</RouterLink>
        <RouterLink v-if="nextReviewDocumentId" class="text-action" :to="`/documents/${nextReviewDocumentId}/review`"
          >继续处理下一篇</RouterLink
        >
      </div>
    </div>
    <div v-else class="publish-layout">
    <section class="panel publish-form">
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
      <fieldset class="publish-channel-field">
        <legend>发布到栏目</legend>
        <div class="publish-channel-options">
          <button v-for="item in channelOptions" :key="item.value" type="button" :class="{ active: form.publishChannel === item.value }" @click="form.publishChannel = item.value">
            <component :is="item.icon" />{{ item.label }}
          </button>
        </div>
        <small v-if="document?.channel_reason">系统建议：{{ document.channel_reason }}<template v-if="document.channel_confidence">（{{ Math.round(document.channel_confidence * 100) }}%）</template></small>
      </fieldset>
      <div class="publish-priority-row">
        <label class="check"><input v-model="form.promoteToRecommend" type="checkbox" />提升到推荐流</label>
        <label class="field">重要程度<select v-model="form.importanceLevel"><option value="NORMAL">普通</option><option value="IMPORTANT">重要</option><option value="URGENT">紧急</option></select></label>
      </div>
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
      <p v-if="imageReviewBlocked" class="inline-error">发布已阻止：网页文章图片尚未完成人工审核。请返回审核页确认图片来源与许可，或改用分类默认图。</p>
      <p v-if="regionScopeBlocked" class="inline-error">发布已阻止：尚未明确居民可见地区，请先设置发布范围。</p>
      <div class="form-actions">
        <button
          class="btn primary"
          :disabled="submitting || !agreed || !form.title || !form.sourceName || imageReviewBlocked || regionScopeBlocked"
          @click="publish"
        >
          <Send :size="17" />{{ submitting ? "正在发布…" : "审核通过并发布" }}
        </button>
      </div>
    </section>
    <aside class="publish-preview" aria-label="用户端发布预览">
      <header><div><Smartphone /><span><b>用户端预览</b><small>发布前确认老人实际看到的内容</small></span></div><div class="preview-size-switch"><button type="button" :class="{ active: previewFont === 20 }" @click="previewFont = 20">普通字号</button><button type="button" :class="{ active: previewFont === 24 }" @click="previewFont = 24"><Type />大字模式</button></div></header>
      <article class="phone-preview" :style="{ fontSize: `${previewFont}px` }">
        <small class="phone-preview__destination">将展示在：首页 &gt; {{ selectedChannelLabel }}</small>
        <span class="phone-preview__category">{{ form.category }}</span>
        <h2>{{ form.title || "待填写标题" }}</h2>
        <p>{{ form.summary || "暂无摘要，请返回审核页确认适老化内容。" }}</p>
        <dl v-if="previewFields.length"><div v-for="field in previewFields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></div></dl>
        <section v-if="previewSteps.length"><h3>接下来怎么做</h3><ol><li v-for="step in previewSteps" :key="step">{{ step }}</li></ol></section>
        <footer><b>来源：{{ form.sourceName || "待确认" }}</b><small>公开日期：{{ form.publishedAt }}</small><span v-if="form.allowPublicOriginal">可查看官方原文或上传原文件</span></footer>
      </article>
    </aside>
    </div>
  </div>
</template>
