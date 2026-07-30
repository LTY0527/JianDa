<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import WebArticleImportPanel from "../components/WebArticleImportPanel.vue";
import { documentApi, type MetadataPreview } from "../api/documents";
import { apiMessage } from "../api/http";
import { currentUser } from "../auth";
import { cleanFilenameTitle } from "../utils/metadata";
import { UploadCloud, FileText, X, ShieldCheck, RefreshCw, SearchCheck } from "lucide-vue-next";
const canImportWeb = computed(() => currentUser()?.role !== "REVIEWER");
const mode = ref<"file" | "web">("file");
const file = ref<File | null>(null),
  title = ref(""),
  sourceName = ref("");
const submitting = ref(false),
  previewing = ref(false),
  error = ref(""),
  metadataError = ref(""),
  metadata = ref<MetadataPreview | null>(null),
  titleDirty = ref(false),
  sourceDirty = ref(false);
const router = useRouter();
let previewSequence = 0;
function selected(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (f) {
    file.value = f;
    titleDirty.value = false;
    sourceDirty.value = false;
    title.value = cleanFilenameTitle(f.name);
    sourceName.value = "";
    error.value = "";
    void previewMetadata(f);
  }
}
async function previewMetadata(target = file.value) {
  if (!target) return;
  const sequence = ++previewSequence;
  previewing.value = true;
  metadataError.value = "";
  try {
    const response = await documentApi.metadataPreview(target);
    if (sequence !== previewSequence || file.value !== target) return;
    metadata.value = response.data.data;
    if (!titleDirty.value && metadata.value.title) title.value = metadata.value.title;
    if (!sourceDirty.value) sourceName.value = metadata.value.source_name || "";
  } catch (cause) {
    if (sequence !== previewSequence) return;
    metadata.value = null;
    metadataError.value = `${apiMessage(cause)}。您仍可手动填写后继续上传。`;
  } finally {
    if (sequence === previewSequence) previewing.value = false;
  }
}
function removeFile() {
  previewSequence++;
  file.value = null;
  metadata.value = null;
  previewing.value = false;
  metadataError.value = "";
  error.value = "";
}
async function submit() {
  if (!file.value || !title.value) return;
  submitting.value = true;
  error.value = "";
  try {
    const created = await documentApi.create(title.value, sourceName.value, metadata.value);
    const id = created.data.data.id;
    await documentApi.upload(id, file.value);
    const processing = await documentApi.process(id);
    await router.push({
      path: `/documents/${id}/process`,
      query: { jobId: processing.data.data.jobId },
    });
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
      title="新增材料"
      description="上传 PDF、图片，或安全导入任意无需登录的公开网页。"
    />
    <div class="import-tabs material-source-tabs" role="tablist" aria-label="材料导入方式">
      <button :class="{ active: mode === 'file' }" @click="mode = 'file'">
        上传 PDF 或图片
      </button>
      <button
        v-if="canImportWeb"
        :class="{ active: mode === 'web' }"
        @click="mode = 'web'"
      >
        导入网页文章
      </button>
    </div>
    <section v-if="mode === 'file'" class="panel form-panel">
      <label class="field"
        >材料标题<input v-model="title" placeholder="例如：老年补贴申请指南" @input="titleDirty = true"
      /></label>
      <div class="field">
        <span>材料文件</span
        ><label class="drop-zone"
          ><input
            type="file"
            accept=".pdf,.png,.jpg,.jpeg"
            @change="selected"
          /><UploadCloud :size="36" /><b>点击选择或拖拽文件到这里</b
          ><small>支持 PDF、PNG、JPG，单个文件不超过 20MB</small></label
        >
        <div v-if="file" class="selected-file">
          <FileText />
          <div>
            <b>{{ file.name }}</b
            ><small>{{ (file.size / 1024).toFixed(1) }} KB · 等待上传</small>
          </div>
          <button type="button" aria-label="移除已选文件" @click="removeFile"><X /></button>
        </div>
      </div>
      <section v-if="previewing" class="metadata-preview loading" role="status">
        <SearchCheck /><div><b>正在识别材料标题和发布机构……</b><small>仅读取首页和末页，不创建正式材料或处理任务。</small></div>
      </section>
      <section v-else-if="metadata" class="metadata-preview">
        <SearchCheck /><div>
          <b>自动识别</b>
          <p>来源依据：{{ metadata.evidence_quote || "未找到明确机构证据" }}</p>
          <small>第 {{ metadata.page_no }} 页 · 置信度 {{ Math.round(metadata.confidence * 100) }}% ·
            {{ metadata.authority_status === "DOCUMENT_EVIDENCE" ? "材料内有发布机构证据" : metadata.authority_status === "CONFLICT" ? "机构候选冲突" : "来源待确认" }}
          </small>
          <small v-if="metadata.document_number">文号：{{ metadata.document_number }}</small>
          <p v-for="warning in metadata.warnings" :key="warning" class="metadata-warning">{{ warning }}</p>
        </div>
        <button type="button" class="btn secondary" @click="previewMetadata()"><RefreshCw :size="16" />重新识别</button>
      </section>
      <div v-else-if="metadataError" class="metadata-preview failed" role="alert">
        <div><b>自动识别未完成</b><p>{{ metadataError }}</p></div>
        <button type="button" class="btn secondary" @click="previewMetadata()"><RefreshCw :size="16" />重试</button>
      </div>
      <label class="field"
        >内容来源<input v-model="sourceName" placeholder="请填写材料发布机构" @input="sourceDirty = true"
      /></label>
      <div class="safe-note">
        <ShieldCheck /><span
          ><b>原始材料将被完整保留</b>AI
          生成内容不会覆盖原文，所有关键字段都可追溯到页码和原文片段。</span
        >
      </div>
      <p v-if="error" class="form-error">{{ error }}</p>

      <div class="form-actions">
        <RouterLink class="btn secondary" to="/documents">取消</RouterLink
        ><button
          class="btn primary"
          :disabled="!file || !title || submitting"
          @click="submit"
        >
          {{ submitting ? "正在上传并处理…" : "上传并开始处理" }}
        </button>
      </div>
    </section>
    <section v-else class="panel form-panel">
      <WebArticleImportPanel />
    </section>
  </div>
</template>
