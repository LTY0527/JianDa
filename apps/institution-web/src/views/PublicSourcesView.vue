<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Plus, ShieldCheck, ToggleLeft, ToggleRight } from "lucide-vue-next";
import PageHeader from "../components/PageHeader.vue";
import { apiMessage } from "../api/http";
import { publicSourceApi, type PublicSource } from "../api/publicSources";

const sources = ref<PublicSource[]>([]);
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const showForm = ref(false);
const form = reactive({ name: "", type: "GOVERNMENT", url: "https://", publisher: "", notes: "" });
const typeText: Record<string, string> = {
  GOVERNMENT: "政府",
  HOSPITAL: "医院",
  MAINSTREAM_MEDIA: "主流媒体",
  PUBLIC_INSTITUTION: "公共机构",
};

async function load() {
  loading.value = true;
  error.value = "";
  try {
    sources.value = (await publicSourceApi.sources()).data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function createSource() {
  saving.value = true;
  error.value = "";
  try {
    await publicSourceApi.createSource(form);
    Object.assign(form, { name: "", type: "GOVERNMENT", url: "https://", publisher: "", notes: "" });
    showForm.value = false;
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

async function toggle(source: PublicSource) {
  try {
    await publicSourceApi.setEnabled(source.id, !source.enabled);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader title="权威来源管理" description="维护可导入公开信息的机构白名单和启用状态。">
      <button class="btn primary" @click="showForm = !showForm"><Plus :size="17" />新增来源</button>
    </PageHeader>

    <form v-if="showForm" class="panel source-create" @submit.prevent="createSource">
      <div class="form-row">
        <label class="field">来源名称<input v-model="form.name" required placeholder="例如：市卫生健康委员会" /></label>
        <label class="field">来源类型<select v-model="form.type"><option v-for="(label, value) in typeText" :key="value" :value="value">{{ label }}</option></select></label>
      </div>
      <div class="form-row">
        <label class="field">来源 URL<input v-model="form.url" required type="url" /></label>
        <label class="field">发布机构<input v-model="form.publisher" required /></label>
      </div>
      <label class="field">备注<textarea v-model="form.notes" rows="2" /></label>
      <div class="form-actions"><button type="button" class="btn secondary" @click="showForm = false">取消</button><button class="btn primary" :disabled="saving">{{ saving ? "正在保存…" : "保存来源" }}</button></div>
    </form>

    <div v-if="error" class="inline-error">{{ error }}</div>
    <section class="panel">
      <table class="data-table source-table">
        <thead><tr><th>来源</th><th>类型</th><th>白名单</th><th>状态</th><th>最近导入</th><th>操作</th></tr></thead>
        <tbody v-if="!loading">
          <tr v-for="source in sources" :key="source.id">
            <td><b>{{ source.source_name }}</b><small>{{ source.publisher }} · {{ source.source_url }}</small></td>
            <td>{{ typeText[source.source_type] || source.source_type }}</td>
            <td><span class="verified"><ShieldCheck :size="15" />已批准</span></td>
            <td>{{ source.enabled ? "已启用" : "已停用" }}</td>
            <td>{{ source.last_imported_at || "尚未导入" }}</td>
            <td><button class="text-action" @click="toggle(source)"><ToggleRight v-if="source.enabled" :size="18" /><ToggleLeft v-else :size="18" />{{ source.enabled ? "停用" : "启用" }}</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">正在加载权威来源…</div>
      <div v-else-if="sources.length === 0" class="empty-state">暂无权威来源，请先新增。</div>
    </section>
  </div>
</template>
