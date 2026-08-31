<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { ExternalLink, Globe2, ImageOff } from "lucide-vue-next";
import { apiMessage } from "../api/http";
import {
  publicSourceApi,
  type WebArticlePreview,
} from "../api/publicSources";
import { documentApi } from "../api/documents";
import RegionTownSelector from "./RegionTownSelector.vue";
import { currentUser } from "../auth";
import {
  contentKindLabel,
  coverTypeLabel,
  formatDisplayDate,
} from "../utils/display";
import { supportedTownCode, townRegionScope } from "../utils/regions";

const emit = defineEmits<{
  imported: [documentId: number];
}>();
const router = useRouter();
const webUrl = ref("");
const preview = ref<WebArticlePreview | null>(null);
const busy = ref<"preview" | "import" | "">("");
const error = ref("");
const coverFailed = ref(false);
const canonicalConfirmed = ref(false);
const showPasteFallback = ref(false);
const pastedTitle = ref("");
const pastedSourceName = ref("");
const pastedBody = ref("");
const pastedContentKind = ref("SERVICE_NOTICE");
const selectedRegionCode = ref("");
const isPlatformAdmin = computed(
  () => currentUser()?.role === "PLATFORM_ADMIN",
);
const isVerified = computed(
  () =>
    preview.value?.trust_status === "VERIFIED" ||
    preview.value?.external_source_verified === true,
);

async function previewArticle() {
  busy.value = "preview";
  error.value = "";
  preview.value = null;
  coverFailed.value = false;
  canonicalConfirmed.value = false;
  try {
    preview.value = (
      await publicSourceApi.previewWebArticle(webUrl.value.trim())
    ).data.data;
    selectedRegionCode.value = supportedTownCode(
      preview.value.registered_source?.region_code,
    );
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    busy.value = "";
  }
}

async function importArticle() {
  if (!preview.value || !selectedRegionCode.value) return;
  busy.value = "import";
  error.value = "";
  try {
    const response = isVerified.value
      ? await publicSourceApi.importWebArticle(webUrl.value.trim())
      : await publicSourceApi.importWebArticleOnce(
          webUrl.value.trim(),
          canonicalConfirmed.value,
        );
    const documentId = response.data.data.documentId;
    await documentApi.updateRegionScope(documentId, townRegionScope(selectedRegionCode.value));
    const processing = await documentApi.process(documentId);
    emit("imported", documentId);
    await router.push({
      path: `/documents/${documentId}/process`,
      query: { imported: "web", jobId: processing.data.data.jobId },
    });
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    busy.value = "";
  }
}

async function verifyAndImport() {
  if (!preview.value || !isPlatformAdmin.value || !selectedRegionCode.value) return;
  if (
    preview.value.canonical_confirmation_required &&
    !canonicalConfirmed.value
  ) {
    error.value = "请先确认网页跳转后的最终域名。";
    return;
  }
  if (!window.confirm("确认该页面来源身份可信，并保存为后续可管理的可信来源吗？")) {
    return;
  }
  busy.value = "import";
  error.value = "";
  try {
    const response = await publicSourceApi.quickConfirmSource({
      url: webUrl.value.trim(),
      sourceName:
        preview.value.source_name ||
        preview.value.canonical_domain ||
        "待核验来源",
      sourceType:
        preview.value.source_type_suggestion || "OTHER_VERIFIED_OFFICIAL",
      verificationNote: "平台管理员在单次网页导入预览中人工核验",
      officialConfirmed: true,
      mode: "SAVE_TRUSTED",
      imageUsagePolicy: "MANUAL_REVIEW",
      imageUsageBasis: "",
      autoApproveImages: false,
      imageCacheAllowed: false,
      continueImport: true,
    });
    const documentId = response.data.data.imported?.documentId;
    if (!documentId) throw new Error("可信来源已保存，但本次网页未创建材料");
    await documentApi.updateRegionScope(documentId, townRegionScope(selectedRegionCode.value));
    const processing = await documentApi.process(documentId);
    emit("imported", documentId);
    await router.push({
      path: `/documents/${documentId}/process`,
      query: { imported: "web", jobId: processing.data.data.jobId },
    });
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    busy.value = "";
  }
}

async function importPastedArticle() {
  if (!selectedRegionCode.value) return;
  busy.value = "import";
  error.value = "";
  try {
    const response = await publicSourceApi.importPastedWebArticle({
      url: webUrl.value.trim(),
      title: pastedTitle.value.trim(),
      sourceName: pastedSourceName.value.trim(),
      body: pastedBody.value.trim(),
      contentKind: pastedContentKind.value,
    });
    const documentId = response.data.data.documentId;
    await documentApi.updateRegionScope(documentId, townRegionScope(selectedRegionCode.value));
    const processing = await documentApi.process(documentId);
    emit("imported", documentId);
    await router.push({
      path: `/documents/${documentId}/process`,
      query: { imported: "pasted-web", jobId: processing.data.data.jobId },
    });
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    busy.value = "";
  }
}
</script>

<template>
  <div class="web-import">
    <form class="web-import__url" @submit.prevent="previewArticle">
      <label class="field"
        >公开网页 URL
        <input
          v-model="webUrl"
          required
          type="url"
          placeholder="粘贴无需登录、可公开访问的 HTTP/HTTPS 网页"
        />
      </label>
      <button class="btn primary" :disabled="busy === 'preview'">
        <Globe2 :size="18" />{{
          busy === "preview" ? "正在识别网页内容…" : "识别网页内容"
        }}
      </button>
    </form>

    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <details v-if="error" class="safe-note">
      <summary>网页无法直接解析时怎么办？</summary>
      <p>
        登录墙、验证码、反爬限制或纯前端页面可能无法自动读取。可粘贴公开正文作为未核验材料，
        或整理为 PDF 后切换到“上传 PDF 或图片”。
      </p>
      <button class="text-action" type="button" @click="showPasteFallback = true">
        粘贴正文导入
      </button>
    </details>

    <form
      v-if="showPasteFallback"
      class="web-import__paste safe-note"
      @submit.prevent="importPastedArticle"
    >
      <h3>粘贴正文导入（未核验）</h3>
      <p>此方式不抓取网页或图片，不会建立可信来源，发布前必须人工核对原文地址和来源。</p>
      <label class="field">文章标题<input v-model="pastedTitle" required maxlength="200" /></label>
      <label class="field">来源名称（可选）<input v-model="pastedSourceName" maxlength="200" /></label>
      <label class="field"
        >内容分类
        <select v-model="pastedContentKind">
          <option value="SERVICE_NOTICE">办事通知</option>
          <option value="HEALTH_EDUCATION">健康科普</option>
          <option value="POLICY_NEWS">养老政策</option>
          <option value="ANTI_FRAUD">防诈提醒</option>
          <option value="COMMUNITY_SERVICE">社区服务</option>
          <option value="CULTURE_EDUCATION">文化学习</option>
        </select>
      </label>
      <label class="field"
        >公开正文
        <textarea v-model="pastedBody" required maxlength="200000" rows="10"></textarea>
      </label>
      <RegionTownSelector v-model="selectedRegionCode" :disabled="busy === 'import'" />
      <div class="form-actions">
        <button class="btn primary" :disabled="busy === 'import' || !selectedRegionCode">导入粘贴正文</button>
        <button class="btn secondary" type="button" @click="showPasteFallback = false">取消</button>
      </div>
    </form>

    <article v-if="preview" class="web-preview-card">
      <div class="web-preview-card__cover">
        <img
          v-if="preview.cover_image_url && !coverFailed"
          :src="preview.cover_image_url"
          :alt="preview.image_alt_text || preview.title"
          referrerpolicy="no-referrer"
          @error="coverFailed = true"
        />
        <div v-else>
          <ImageOff /><span>原图不可用，将使用本地分类默认图</span>
        </div>
      </div>
      <div class="web-preview-card__content">
        <div class="web-preview-card__source">
          <b>{{ preview.source_name }}</b>
          <span>{{
            isVerified ? "已核验来源" : "未核验网页 · 仅本次导入"
          }}</span>
        </div>
        <h2>{{ preview.title }}</h2>
        <p>{{ preview.content_preview }}</p>
        <dl>
          <div><dt>内容分类</dt><dd>{{ contentKindLabel(preview.content_kind) }}</dd></div>
          <div><dt>原始域名</dt><dd>{{ preview.original_domain || "—" }}</dd></div>
          <div><dt>最终域名</dt><dd>{{ preview.canonical_domain || "—" }}</dd></div>
          <div>
            <dt>原始发布时间</dt>
            <dd>{{ formatDisplayDate(preview.published_at) }}</dd>
          </div>
          <div><dt>封面类型</dt><dd>{{ coverTypeLabel(preview.cover_image_type) }}</dd></div>
          <div><dt>图片候选</dt><dd>{{ preview.images?.length || 0 }} 张</dd></div>
        </dl>
        <RegionTownSelector v-model="selectedRegionCode" :disabled="busy === 'import'" />
        <label
          v-if="preview.canonical_confirmation_required"
          class="canonical-confirm"
        >
          <input v-model="canonicalConfirmed" type="checkbox" />
          我已核对跳转后的最终域名，确认继续本次导入
        </label>
        <details class="technical-info">
          <summary>技术信息</summary>
          <dl>
            <div><dt>规范网址</dt><dd>{{ preview.canonical_url }}</dd></div>
            <div><dt>抓取规则</dt><dd>{{ preview.robots_status }}</dd></div>
          </dl>
        </details>
        <p
          v-for="warning in preview.warnings"
          :key="warning"
          class="web-preview-card__warning"
        >
          {{ warning }}
        </p>
        <div class="web-preview-card__actions">
          <a
            class="btn secondary"
            :href="preview.canonical_url"
            target="_blank"
            rel="noopener noreferrer"
            ><ExternalLink :size="17" />查看原网页</a
          >
          <button
            class="btn primary"
            :disabled="
              busy === 'import' ||
              !selectedRegionCode ||
              (!!preview.canonical_confirmation_required &&
                !canonicalConfirmed)
            "
            @click="importArticle"
          >
            {{
              busy === "import"
                ? "正在导入并创建任务…"
                : isVerified
                  ? "从可信来源导入"
                  : "仅本次导入"
            }}
          </button>
          <button
            v-if="!isVerified && isPlatformAdmin"
            class="btn secondary"
            :disabled="
              busy === 'import' ||
              !selectedRegionCode ||
              (!!preview.canonical_confirmation_required &&
                !canonicalConfirmed)
            "
            @click="verifyAndImport"
          >
            核验并保存为可信来源
          </button>
        </div>
        <small
          >未核验网页不会创建可信来源或进入自动采集；导入后仍须完成人工字段、来源和图片审核。</small
        >
      </div>
    </article>
  </div>
</template>
