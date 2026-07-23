<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import { documentApi } from "../api/documents";
import { apiMessage } from "../api/http";
import { UploadCloud, FileText, X, ShieldCheck } from "lucide-vue-next";
const file = ref<File | null>(null),
  title = ref("");
const submitting = ref(false),
  error = ref("");
const router = useRouter();
function selected(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (f) {
    file.value = f;
    title.value = f.name.replace(/\.[^.]+$/, "");
  }
}
async function submit() {
  if (!file.value || !title.value) return;
  submitting.value = true;
  error.value = "";
  try {
    const created = await documentApi.create(title.value);
    const id = created.data.data.id;
    await documentApi.upload(id, file.value);
    await documentApi.process(id);
    await router.push(`/documents/${id}/process`);
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
      title="上传材料"
      description="上传 PDF 或图片后，系统将保存原件并创建 AI 处理任务。"
    />
    <section class="panel form-panel">
      <label class="field"
        >材料标题<input v-model="title" placeholder="例如：老年补贴申请指南"
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
          <button type="button" aria-label="移除已选文件" @click="file = null"><X /></button>
        </div>
      </div>
      <label class="field"
        >内容来源<input value="浦江街道社区服务中心"
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
  </div>
</template>
