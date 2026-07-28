<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ExternalLink, Globe2, ImageOff } from "lucide-vue-next";
import { apiMessage } from "../api/http";
import {
  publicSourceApi,
  type WebArticlePreview,
} from "../api/publicSources";
import { documentApi } from "../api/documents";
import {
  authorityLevelLabel,
  contentKindLabel,
  coverTypeLabel,
  formatDisplayDate,
} from "../utils/display";

const emit = defineEmits<{
  imported: [documentId: number];
}>();
const router = useRouter();
const webUrl = ref("");
const preview = ref<WebArticlePreview | null>(null);
const busy = ref<"preview" | "import" | "">("");
const error = ref("");
const coverFailed = ref(false);

async function previewArticle() {
  busy.value = "preview";
  error.value = "";
  preview.value = null;
  coverFailed.value = false;
  try {
    preview.value = (
      await publicSourceApi.previewWebArticle(webUrl.value.trim())
    ).data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    busy.value = "";
  }
}

async function importArticle() {
  if (!preview.value) return;
  busy.value = "import";
  error.value = "";
  try {
    const response = await publicSourceApi.importWebArticle(webUrl.value.trim());
    const documentId = response.data.data.documentId;
    await documentApi.process(documentId);
    emit("imported", documentId);
    await router.push({
      path: `/documents/${documentId}/process`,
      query: { imported: "web" },
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
        >官方文章 URL
        <input
          v-model="webUrl"
          required
          type="url"
          placeholder="仅支持已加入白名单的官方政府或中央媒体域名"
        />
      </label>
      <button class="btn primary" :disabled="busy === 'preview'">
        <Globe2 :size="18" />{{
          busy === "preview" ? "正在识别网页内容…" : "识别网页内容"
        }}
      </button>
    </form>

    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>

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
          <span>{{ authorityLevelLabel(preview.authority_level) }}</span>
        </div>
        <h2>{{ preview.title }}</h2>
        <p>{{ preview.content_preview }}</p>
        <dl>
          <div><dt>内容分类</dt><dd>{{ contentKindLabel(preview.content_kind) }}</dd></div>
          <div>
            <dt>原始发布时间</dt>
            <dd>{{ formatDisplayDate(preview.published_at) }}</dd>
          </div>
          <div><dt>封面类型</dt><dd>{{ coverTypeLabel(preview.cover_image_type) }}</dd></div>
        </dl>
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
            ><ExternalLink :size="17" />查看官方原文</a
          >
          <button
            class="btn primary"
            :disabled="busy === 'import'"
            @click="importArticle"
          >
            {{
              busy === "import" ? "正在导入并创建任务…" : "导入并开始处理"
            }}
          </button>
        </div>
        <small
          >预览不会创建正式材料；导入后仍须完成人工字段、来源和图片审核。</small
        >
      </div>
    </article>
  </div>
</template>
