<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Plus, ShieldCheck, ToggleLeft, ToggleRight } from "lucide-vue-next";
import PageHeader from "../components/PageHeader.vue";
import { apiMessage } from "../api/http";
import {
  publicSourceApi,
  type CrawlJob,
  type PublicSource,
  type SourceRegistryPayload,
  type WebSourceRegistry,
} from "../api/publicSources";
import {
  formatDisplayDateTime,
  statusLabel,
} from "../utils/display";

const sources = ref<PublicSource[]>([]);
const registries = ref<WebSourceRegistry[]>([]);
const jobs = ref<CrawlJob[]>([]);
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const showForm = ref(false);
const editingRegistryId = ref<number | null>(null);
const form = reactive({ name: "", type: "GOVERNMENT", url: "https://", publisher: "", notes: "" });
const registryForm = reactive<SourceRegistryPayload>({
  name: "", domain: "", type: "PUBLIC_INSTITUTION", authorityLevel: "B",
  homepageUrl: "https://", rssUrl: "", sitemapUrl: "", sectionUrl: "",
  discoveryMode: "MANUAL", dailyCrawlTime: "03:30", maxArticlesPerRun: 5,
  allowImageCandidates: false, allowAutoAi: false, dailyArticleBudget: 0, dailyTokenBudget: 0,
});
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
    const [sourceResponse, registryResponse, jobResponse] = await Promise.all([
      publicSourceApi.sources(),
      publicSourceApi.webRegistries(),
      publicSourceApi.crawlJobs(),
    ]);
    sources.value = sourceResponse.data.data;
    registries.value = registryResponse.data.data;
    jobs.value = jobResponse.data.data;
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

async function saveRegistry() {
  saving.value = true;
  error.value = "";
  try {
    if (editingRegistryId.value) await publicSourceApi.updateWebRegistry(editingRegistryId.value, registryForm);
    else await publicSourceApi.createWebRegistry(registryForm);
    editingRegistryId.value = null;
    Object.assign(registryForm, {
      name: "", domain: "", type: "PUBLIC_INSTITUTION", authorityLevel: "B", homepageUrl: "https://",
      rssUrl: "", sitemapUrl: "", sectionUrl: "", discoveryMode: "MANUAL", dailyCrawlTime: "03:30",
      maxArticlesPerRun: 5, allowImageCandidates: false, dailyArticleBudget: 0, dailyTokenBudget: 0,
    });
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    saving.value = false;
  }
}

function editRegistry(source: WebSourceRegistry) {
  editingRegistryId.value = source.id;
  Object.assign(registryForm, {
    name: source.source_name, domain: source.domain, type: source.source_type,
    authorityLevel: source.authority_level, homepageUrl: source.homepage_url,
    rssUrl: source.rss_url || "", sitemapUrl: source.sitemap_url || "", sectionUrl: source.section_url || "",
    discoveryMode: source.discovery_mode, dailyCrawlTime: source.daily_crawl_time,
    maxArticlesPerRun: source.max_articles_per_run, allowImageCandidates: source.allow_image_candidates,
    allowAutoAi: source.allow_auto_ai, dailyArticleBudget: source.daily_article_budget, dailyTokenBudget: source.daily_token_budget,
  });
}

async function toggleRegistry(source: WebSourceRegistry) {
  const action = source.enabled ? "停用" : "启用";
  if (!window.confirm(`确认${action}“${source.source_name}”吗？`)) return;
  try {
    await publicSourceApi.setWebRegistryEnabled(source.id, !source.enabled);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function stopJob(job: CrawlJob) {
  try {
    await publicSourceApi.stopCrawlJob(job.id);
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
    <section class="panel">
      <div class="panel-title"><div><h2>网页白名单来源</h2><p>维护调度入口、文章上限和自动处理预算；新来源默认停用。</p></div></div>
      <form class="source-create" @submit.prevent="saveRegistry">
        <div class="form-row">
          <label class="field">来源名称<input v-model="registryForm.name" required /></label>
          <label class="field">完整域名<input v-model="registryForm.domain" required placeholder="www.example.gov.cn" /></label>
        </div>
        <div class="form-row">
          <label class="field">来源类型<select v-model="registryForm.type"><option v-for="(label, value) in typeText" :key="value" :value="value">{{ label }}</option></select></label>
          <label class="field">发现方式<select v-model="registryForm.discoveryMode"><option value="MANUAL">手动</option><option value="RSS">RSS</option><option value="ATOM">Atom</option><option value="SITEMAP">Sitemap</option><option value="SECTION">栏目页</option><option value="MIXED">混合</option></select></label>
        </div>
        <label class="field">主页地址<input v-model="registryForm.homepageUrl" type="url" required /></label>
        <div class="form-row"><label class="field">RSS / Atom 地址<input v-model="registryForm.rssUrl" type="url" /></label><label class="field">Sitemap 地址<input v-model="registryForm.sitemapUrl" type="url" /></label></div>
        <label class="field">栏目页地址<input v-model="registryForm.sectionUrl" type="url" /></label>
        <div class="form-row"><label class="field">每日采集时间<input v-model="registryForm.dailyCrawlTime" type="time" required /></label><label class="field">每轮文章上限<input v-model.number="registryForm.maxArticlesPerRun" type="number" min="1" max="100" /></label></div>
        <div class="form-row"><label class="field">每日文章预算<input v-model.number="registryForm.dailyArticleBudget" type="number" min="0" /></label><label class="field">每日 Token 预算<input v-model.number="registryForm.dailyTokenBudget" type="number" min="0" /></label></div>
        <label class="field"><input v-model="registryForm.allowImageCandidates" type="checkbox" /> 允许生成图片候选（仍需人工确认版权）</label>
        <label class="field"><input v-model="registryForm.allowAutoAi" type="checkbox" /> 允许自动 AI（仅在全局开关和预算同时允许时生效）</label>
        <div class="form-actions"><button v-if="editingRegistryId" type="button" class="btn secondary" @click="editingRegistryId = null">取消编辑</button><button class="btn primary" :disabled="saving">{{ saving ? "正在保存…" : editingRegistryId ? "保存修改" : "新增运营来源" }}</button></div>
      </form>
      <table class="data-table">
        <thead><tr><th>来源</th><th>调度</th><th>预算</th><th>最近状态</th><th>错误摘要</th><th>操作</th></tr></thead>
        <tbody v-if="!loading">
          <tr v-for="source in registries" :key="source.id">
            <td><b>{{source.source_name}}</b><small>{{source.domain}} · {{typeText[source.source_type] || source.source_type}}</small></td>
            <td>{{source.enabled ? "已启用" : "已停用"}} · {{source.discovery_mode}}<small>{{source.daily_crawl_time}} / 每轮 {{source.max_articles_per_run}} 篇</small></td>
            <td>每日 {{source.daily_article_budget}} 篇<small>{{source.daily_token_budget.toLocaleString()}} Token · 自动 AI {{source.allow_auto_ai ? "开启" : "关闭"}}</small></td>
            <td>{{statusLabel(source.last_status)}}<small>最近 {{formatDisplayDateTime(source.last_crawled_at)}} · 下次 {{formatDisplayDateTime(source.next_run_at)}}</small></td>
            <td>{{source.last_error||"无"}}</td>
            <td><button class="text-action" @click="editRegistry(source)">编辑</button><button class="text-action" @click="toggleRegistry(source)">{{source.enabled ? "停用" : "启用"}}</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">正在加载运营来源…</div>
      <div v-else-if="registries.length === 0" class="empty-state">暂无运营来源，请先新增。</div>
    </section>
    <section class="panel">
      <div class="panel-title"><div><h2>采集任务</h2><p>查看成功、未变化、失败和等待审核状态。</p></div></div>
      <table class="data-table">
        <thead><tr><th>来源</th><th>文章地址</th><th>状态</th><th>最后成功</th><th>错误</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="job in jobs" :key="job.id">
            <td>{{job.source_name}}</td><td><a :href="job.original_url" target="_blank" rel="noreferrer">查看官方页面</a></td>
            <td>{{statusLabel(job.status)}}</td><td>{{formatDisplayDateTime(job.last_success_at)}}</td>
            <td>{{job.last_error||"无"}}</td>
            <td><button v-if="['QUEUED','RUNNING'].includes(job.status)" class="text-action danger" @click="stopJob(job)">停止任务</button><RouterLink v-else-if="job.document_id" :to="`/documents/${job.document_id}/process`">查看材料</RouterLink></td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
