<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowRight, Check, Circle, FileUp, Link, LoaderCircle, RefreshCw } from "lucide-vue-next";
import PageHeader from "../components/PageHeader.vue";
import { apiMessage } from "../api/http";
import {
  publicSourceApi,
  type ArticleDiscoveryCandidate,
  type BatchImportJob,
  type CrawlJob,
  type WebSourceRegistry,
} from "../api/publicSources";

const route = useRoute();
const router = useRouter();
const sourceId = Number(route.params.sourceId);
const jobId = Number(route.params.jobId);
const source = ref<WebSourceRegistry | null>(null);
const job = ref<CrawlJob | null>(null);
const selectedUrls = ref<string[]>([]);
const loading = ref(true);
const collecting = ref(false);
const error = ref("");
const notice = ref("");
const importedDocuments = ref<number[]>([]);
const importJob = ref<BatchImportJob | null>(null);
let discoveryTimer: ReturnType<typeof setTimeout> | undefined;
let importTimer: ReturnType<typeof setTimeout> | undefined;

const terminal = computed(() => job.value && !["PENDING", "RUNNING"].includes(job.value.status));
const succeeded = computed(() => ["SUCCESS", "PARTIAL_SUCCESS"].includes(job.value?.status || ""));
const candidates = computed(() => job.value?.discoveryResult?.candidates || []);
const newCandidates = computed(() => candidates.value.filter((item) => !item.imported));
const failedReason = computed(() => job.value?.errors?.[0]?.error_summary || job.value?.last_error || "官网暂时无法读取");
const filteredCount = computed(() =>
  (job.value?.discoveryResult?.filtered_external_count || 0)
  + (job.value?.discoveryResult?.filtered_navigation_count || 0));

const stageOrder = [
  { key: "CONNECT", label: "正在连接官网" },
  { key: "DISCOVERY", label: "正在读取栏目" },
  { key: "PARSE", label: "正在识别文章" },
  { key: "DEDUP", label: "正在去重" },
  { key: "COMPLETE", label: "正在整理结果" },
];

function stageIndex() {
  if (terminal.value) return stageOrder.length;
  const stage = (job.value?.processing_stage || "").toUpperCase();
  if (stage.includes("DEDUP")) return 3;
  if (stage.includes("PARSE") || stage.includes("ARTICLE")) return 2;
  if (stage.includes("DISCOVER") || stage.includes("FETCH") || stage.includes("READ")) return 1;
  return 0;
}

async function load() {
  try {
    const [jobResponse, sourcesResponse] = await Promise.all([
      publicSourceApi.registryDiscoveryJob(jobId),
      source.value ? Promise.resolve(null) : publicSourceApi.webRegistries(),
    ]);
    job.value = jobResponse.data.data;
    if (sourcesResponse) source.value = sourcesResponse.data.data.find((item) => item.id === sourceId) || null;
    error.value = "";
    if (!terminal.value) discoveryTimer = setTimeout(load, 1200);
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

function selectAll() {
  selectedUrls.value = newCandidates.value
    .filter((item) => item.relevance_level !== "LOW")
    .map((item) => item.canonical_url);
}

async function collectOne(article: ArticleDiscoveryCandidate) {
  if (!source.value) return;
  collecting.value = true;
  error.value = "";
  try {
    const response = await publicSourceApi.collectRegistryArticle(source.value.id, article.canonical_url);
    importedDocuments.value = [response.data.data.documentId];
    notice.value = `材料 #${response.data.data.documentId} 已加入内容中心，尚未发布。`;
    article.imported = true;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    collecting.value = false;
  }
}

async function collectSelected() {
  if (!source.value || !selectedUrls.value.length) return;
  if (!window.confirm(`确认将所选 ${selectedUrls.value.length} 篇加入内容中心吗？内容仍需 AI 处理和人工审核。`)) return;
  collecting.value = true;
  error.value = "";
  try {
    const response = await publicSourceApi.collectRegistryArticles(source.value.id, selectedUrls.value);
    localStorage.setItem(`jianda_import_job_${source.value.id}`, String(response.data.data.jobId));
    notice.value = `批量加入任务 #${response.data.data.jobId} 已创建，可离开页面后再回来查看。`;
    selectedUrls.value = [];
    await pollImportJob(response.data.data.jobId);
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    collecting.value = false;
  }
}

async function pollImportJob(activeJobId: number) {
  try {
    const response = await publicSourceApi.registryImportJob(activeJobId);
    importJob.value = response.data.data;
    const current = importJob.value;
    if (["PENDING", "RUNNING"].includes(current.status)) {
      importTimer = setTimeout(() => pollImportJob(activeJobId), 1200);
      return;
    }
    localStorage.removeItem(`jianda_import_job_${sourceId}`);
    importedDocuments.value = current.result?.imported?.map((item) => item.documentId) || [];
    notice.value = `已加入 ${current.added_count} 篇，重复 ${current.duplicate_count} 篇，失败 ${current.failed_count} 篇；不会自动发布。`;
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

async function retry() {
  if (!source.value) return;
  const method = source.value.discovery_mode === "MANUAL" ? "SECTION" : source.value.discovery_mode;
  const entryUrl = method === "RSS" || method === "ATOM"
    ? source.value.rss_url
    : method === "SITEMAP" ? source.value.sitemap_url : source.value.section_url || source.value.homepage_url;
  try {
    const response = await publicSourceApi.startRegistryDiscoveryJob(source.value.id, {
      method, entryUrl: entryUrl || source.value.homepage_url,
      recentDays: source.value.recent_days, maxArticles: source.value.max_articles_per_run,
      includeKeywords: source.value.include_keywords || "", excludeKeywords: source.value.exclude_keywords || "",
      onlyUnimported: true,
    });
    await router.replace(`/public-sources/${source.value.id}/check/${response.data.data.id}`);
    location.reload();
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}

onMounted(() => {
  void load();
  const saved = Number(localStorage.getItem(`jianda_import_job_${sourceId}`) || 0);
  if (saved > 0) void pollImportJob(saved);
});
onUnmounted(() => {
  if (discoveryTimer) clearTimeout(discoveryTimer);
  if (importTimer) clearTimeout(importTimer);
});
</script>

<template>
  <main class="discovery-page">
    <PageHeader :title="`检查${source ? `“${source.source_name}”` : '官方来源'}`" description="检查任务会在后台继续运行，离开此页不会中断。" :breadcrumbs="['采集与来源', '来源检查']">
      <RouterLink class="btn secondary" to="/public-sources">返回来源</RouterLink>
    </PageHeader>

    <div v-if="error" class="inline-error" role="alert">{{ error }}</div>
    <section v-if="loading && !job" class="panel discovery-loading"><LoaderCircle class="spin" />正在读取任务状态…</section>

    <section v-else-if="job && !terminal" class="panel discovery-progress" aria-live="polite">
      <header><LoaderCircle class="spin" /><div><h2>{{ job.progress_message || "正在检查官方来源" }}</h2><p>任务 #{{ job.id }} · 你可以离开此页面，任务会继续运行。</p></div></header>
      <ol>
        <li v-for="(stage, index) in stageOrder" :key="stage.key" :class="{ done: index < stageIndex(), active: index === stageIndex() }">
          <Check v-if="index < stageIndex()" /><LoaderCircle v-else-if="index === stageIndex()" class="spin" /><Circle v-else />
          {{ stage.label }}
        </li>
      </ol>
    </section>

    <section v-else-if="job?.status === 'FAILED'" class="panel discovery-failed">
      <h2>本次检查没有完成</h2><p>原因：{{ failedReason }}</p>
      <p class="safe-note">可以稍后重试，也可以直接粘贴文章链接或上传官方 PDF 继续工作。</p>
      <div class="discovery-actions">
        <button class="btn primary" type="button" @click="retry"><RefreshCw />重新检查</button>
        <RouterLink class="btn secondary" to="/public-import"><Link />粘贴文章链接</RouterLink>
        <RouterLink class="btn secondary" to="/documents/upload"><FileUp />上传 PDF</RouterLink>
        <RouterLink class="text-action" to="/public-sources">返回来源</RouterLink>
      </div>
      <details v-if="job.errors?.length"><summary>查看技术信息</summary><code>{{ job.errors[0].error_code }} · {{ job.errors[0].processing_stage }}</code></details>
    </section>

    <template v-else-if="job && succeeded">
      <section class="panel discovery-summary">
        <div><span class="success-icon"><Check /></span><div><h2>检查完成</h2><p>发现 {{ job.discovered_count }} 篇 · 已有 {{ job.duplicate_count }} 篇 · 新增 {{ job.added_count }} 篇</p></div></div>
        <p v-if="filteredCount" class="safe-note">已合并过滤 {{ filteredCount }} 条导航、目录或非白名单链接，详情保留在任务技术记录中。</p>
      </section>

      <section class="panel discovery-results">
        <header><div><h2>{{ newCandidates.length }} 篇新内容</h2><p>选择后加入内容中心；系统不会自动发布。</p></div><button class="btn secondary" type="button" @click="selectAll">全选推荐内容</button></header>
        <div v-if="!candidates.length" class="empty-state">没有发现可加入的新文章。可调整来源入口，或改用粘贴链接和上传 PDF。</div>
        <article v-for="article in candidates" :key="article.dedup_key" class="candidate-row">
          <input v-model="selectedUrls" type="checkbox" :value="article.canonical_url" :disabled="article.imported" :aria-label="`选择${article.title || '文章'}`" />
          <div><h3>{{ article.title || "标题待抓取" }}</h3><p>{{ article.published_time || "发布时间待核对" }} · {{ article.region_name || source?.source_name }} · {{ article.recommended_topic || "公共服务" }}</p><p class="candidate-reason">{{ article.recommendation_reason }}</p><small>{{ article.canonical_url }}</small></div>
          <span :class="article.imported ? 'candidate-existing' : `candidate-relevance relevance-${(article.relevance_level || 'MEDIUM').toLowerCase()}`">{{ article.imported ? "已存在" : article.relevance_level === 'HIGH' ? '高相关' : article.relevance_level === 'LOW' ? '低相关' : '中相关' }}</span>
          <button class="text-action strong" type="button" :disabled="article.imported || collecting" @click="collectOne(article)">加入内容中心</button>
        </article>
        <footer v-if="candidates.length"><button class="btn primary" type="button" :disabled="!selectedUrls.length || collecting" @click="collectSelected">加入所选内容（{{ selectedUrls.length }}）</button></footer>
      </section>
    </template>

    <section v-if="importJob && ['PENDING', 'RUNNING'].includes(importJob.status)" class="panel discovery-progress import-progress" aria-live="polite">
      <header><LoaderCircle class="spin" /><div><h2>{{ importJob.progress_message || '正在准备批量加入' }}</h2><p>任务 #{{ importJob.id }} · 刷新或离开页面不会中断。</p></div></header>
      <p>已处理 {{ importJob.added_count + importJob.duplicate_count + importJob.failed_count }}/{{ importJob.discovered_count }} · 成功 {{ importJob.added_count }} · 重复 {{ importJob.duplicate_count }} · 失败 {{ importJob.failed_count }}</p>
    </section>

    <section v-if="notice" class="panel collect-next-step" role="status">
      <Check /><div><h2>已加入内容中心</h2><p>{{ notice }}</p></div>
      <div class="discovery-actions">
        <RouterLink v-if="importedDocuments.length === 1" class="btn primary" :to="`/documents/${importedDocuments[0]}/process`">立即处理<ArrowRight /></RouterLink>
        <RouterLink class="btn secondary" to="/documents">继续查看</RouterLink>
        <RouterLink class="text-action" to="/public-sources">返回来源</RouterLink>
      </div>
    </section>
  </main>
</template>

<style scoped>
.discovery-page{max-width:1180px;margin:0 auto}.discovery-loading{display:flex;align-items:center;gap:10px;padding:36px}.discovery-progress,.discovery-failed,.discovery-summary,.discovery-results,.collect-next-step{padding:26px;margin-bottom:18px}.discovery-progress header,.discovery-summary>div,.collect-next-step{display:flex;align-items:flex-start;gap:14px}.discovery-progress h2,.discovery-summary h2,.discovery-failed h2,.collect-next-step h2{margin:0 0 6px}.discovery-progress p,.discovery-summary p,.collect-next-step p{margin:0;color:var(--color-muted)}.discovery-progress ol{display:grid;grid-template-columns:repeat(5,1fr);gap:0;margin:30px 0 4px;padding:0;list-style:none}.discovery-progress li{position:relative;display:grid;justify-items:center;gap:8px;color:var(--color-muted);text-align:center;font-size:13px}.discovery-progress li:not(:last-child)::after{content:"";position:absolute;top:12px;left:62%;width:76%;height:2px;background:var(--color-border)}.discovery-progress li.done,.discovery-progress li.active{color:var(--color-primary);font-weight:700}.discovery-progress li.done::after{background:var(--color-primary)}.discovery-progress svg{width:25px;height:25px;padding:3px;background:#fff;z-index:1}.discovery-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:18px}.success-icon{display:grid;place-items:center;width:40px;height:40px;border-radius:50%;background:#e1f2ed;color:var(--color-primary)}.discovery-results>header{display:flex;align-items:center;justify-content:space-between;gap:18px;padding-bottom:16px;border-bottom:1px solid var(--color-border)}.discovery-results h2,.discovery-results h3{margin:0}.discovery-results header p{margin:5px 0 0;color:var(--color-muted)}.candidate-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto auto;gap:16px;align-items:center;padding:18px 0;border-bottom:1px solid var(--color-border)}.candidate-row p,.candidate-row small{display:block;margin:5px 0 0;color:var(--color-muted)}.candidate-row small{overflow-wrap:anywhere}.candidate-new,.candidate-existing{padding:4px 8px;border-radius:5px;font-size:12px}.candidate-new{background:#e7f3ef;color:var(--color-primary)}.candidate-existing{background:#f0f2f1;color:var(--color-muted)}.discovery-results footer{display:flex;justify-content:flex-end;padding-top:18px}.collect-next-step{align-items:center}.collect-next-step>.discovery-actions{margin:0 0 0 auto}.collect-next-step>svg{color:var(--color-primary)}
.candidate-relevance{padding:4px 8px;border-radius:5px;font-size:12px;font-weight:700}.relevance-high{background:#e1f2ed;color:#12634f}.relevance-medium{background:#fff1cc;color:#765400}.relevance-low{background:#f1f2f2;color:#68706d}.candidate-reason{font-size:13px}
@media(max-width:760px){.discovery-progress ol{grid-template-columns:1fr;gap:14px}.discovery-progress li{grid-template-columns:30px 1fr;justify-items:start;text-align:left}.discovery-progress li::after{display:none}.candidate-row{grid-template-columns:auto minmax(0,1fr)}.candidate-row>span,.candidate-row>button{grid-column:2;justify-self:start}.discovery-results>header,.collect-next-step{align-items:flex-start;flex-direction:column}.collect-next-step>.discovery-actions{margin-left:0}}
</style>
