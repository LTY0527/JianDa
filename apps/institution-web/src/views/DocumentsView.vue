<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { Search, Plus, Upload, Globe2, FileImage, FileText, PenLine, X, RefreshCw, Zap, MapPin, Layers, Timer } from "lucide-vue-next";
import { formatDisplayDate, formatDisplayDateTime, statusLabel, stageLabel, channelLabel, estimateEtaMinutes } from "../utils/display";
import { isPlatformAdmin } from "../auth";
import { useRoute } from "vue-router";
import { publicSourceApi } from "../api/publicSources";
const route = useRoute();
const query = ref("");
const status = ref("ALL");
const showAddContent = ref(false);
const loading = ref(true);
const error = ref("");
const documents = ref<DocumentRow[]>([]);
const importDocumentIds = ref<number[] | null>(null);
const importJobId = computed(() => Number(route.query.importJobId) || 0);
const refreshing = ref(false);
const lastUpdatedAt = ref<Date | null>(null);
const savedState = sessionStorage.getItem("jianda_documents_state");
if (savedState) {
  const parsed = JSON.parse(savedState);
  query.value = parsed.query || "";
  status.value = parsed.status || "ALL";
}
if (typeof route.query.status === "string" && ["ALL", "PENDING", "WAITING_REVIEW", "WAITING_PUBLISH", "PUBLISHED", "EXCEPTION"].includes(route.query.status)) {
  status.value = route.query.status;
}
watch([query, status], () => sessionStorage.setItem("jianda_documents_state", JSON.stringify({ query: query.value, status: status.value, scroll: window.scrollY })));
onUnmounted(() => sessionStorage.setItem("jianda_documents_state", JSON.stringify({ query: query.value, status: status.value, scroll: window.scrollY })));
const filtered = computed(() =>
  documents.value.filter(
    (d) =>
      (importDocumentIds.value === null || importDocumentIds.value.includes(d.id)) &&
      (!query.value || d.title.includes(query.value)) &&
      (status.value === "ALL" || statusGroups[status.value]?.includes(d.status)),
  ),
);
const statusGroups: Record<string, string[]> = {
  PENDING: ["UPLOADED", "PROCESSING"],
  WAITING_REVIEW: ["WAITING_REVIEW"],
  WAITING_PUBLISH: ["REVIEWED"],
  PUBLISHED: ["PUBLISHED"],
  EXCEPTION: ["FAILED", "WITHDRAWN"],
};
const tabs = [
  ["ALL", "全部"],
  ["PENDING", "待处理"],
  ["WAITING_REVIEW", "待审核"],
  ["WAITING_PUBLISH", "待发布"],
  ["PUBLISHED", "已发布"],
  ["EXCEPTION", "异常"],
] as const;
function tabCount(key: string) {
  if (key === "ALL") return documents.value.length;
  return documents.value.filter((item) => statusGroups[key]?.includes(item.status)).length;
}
function displayProgress(document: DocumentRow) {
  return ["WAITING_REVIEW", "REVIEWED", "PUBLISHED", "WITHDRAWN"].includes(
    document.status,
  )
    ? 100
    : document.progress;
}
function sourceIcon(document: DocumentRow) {
  return document.source_type === "WEB_ARTICLE"
    ? Globe2
    : document.source_type === "IMAGE"
      ? FileImage
      : FileText;
}
function sourceLabel(document: DocumentRow) {
  if (document.source_type === "WEB_ARTICLE") return "网页文章";
  if (document.source_type === "IMAGE") return "图片材料";
  return "PDF 材料";
}
function isProcessing(document: DocumentRow) {
  return document.status === "UPLOADED" || document.status === "PROCESSING" || document.status === "QUEUED";
}
function processHref(document: DocumentRow) {
  if (document.status === "WAITING_REVIEW") return `/documents/${document.id}/review`;
  return `/documents/${document.id}/process`;
}
async function refreshDocuments(initial = false) {
  if (refreshing.value) return;
  refreshing.value = true;
  if (initial) loading.value = true;
  try {
    documents.value = (await documentApi.list()).data.data;
    lastUpdatedAt.value = new Date();
    error.value = "";
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
}
onMounted(async () => {
  if (importJobId.value) {
    try {
      const job = (await publicSourceApi.registryImportJob(importJobId.value)).data.data;
      importDocumentIds.value = (job.result?.imported || []).map((item) => item.documentId);
    } catch (cause) {
      error.value = apiMessage(cause);
      importDocumentIds.value = [];
    }
  }
  await refreshDocuments(true);
  try {
    requestAnimationFrame(() => window.scrollTo(0, Number(savedState ? JSON.parse(savedState).scroll : 0)));
  } catch { /* Ignore malformed legacy scroll state. */ }
});
</script>
<template>
  <div>
    <PageHeader
      title="内容中心"
      description="在一个地方查看上传材料、网页内容和已发布信息。"
      ><button class="btn secondary" type="button" :disabled="refreshing" @click="refreshDocuments()"><RefreshCw :size="17" :class="{ spin: refreshing }" />{{ refreshing ? "正在刷新…" : "刷新" }}</button><button class="btn primary" type="button" @click="showAddContent = true"
        ><Plus :size="17" />添加内容</button
      ></PageHeader
    >
    <p v-if="lastUpdatedAt" class="content-updated" role="status">{{ refreshing ? "正在同步最新状态" : `最近更新 ${lastUpdatedAt.toLocaleTimeString('zh-CN', { hour12: false })}` }}</p>
    <p v-if="importJobId" class="content-updated import-filter" role="status">
      正在显示导入任务 #{{ importJobId }} 的 {{ importDocumentIds?.length || 0 }} 篇新材料。
      <RouterLink to="/documents">查看全部内容</RouterLink>
    </p>
    <section class="panel">
      <nav class="content-tabs" aria-label="内容状态">
        <button
          v-for="tab in tabs"
          :key="tab[0]"
          type="button"
          :class="{ active: status === tab[0] }"
          @click="status = tab[0]"
        >{{ tab[1] }} <span>{{ tabCount(tab[0]) }}</span></button>
      </nav>
      <div class="filters">
        <div class="search">
          <Search :size="18" /><input
            v-model="query"
            aria-label="搜索材料标题或文件名"
            placeholder="搜索材料标题、来源、地区或栏目"
          />
        </div>
      </div>
      <div class="table-wrap">
        <table class="data-table dense">
          <thead>
            <tr>
              <th style="min-width:280px">材料</th>
              <th style="min-width:140px"><span class="th-icon"><Layers :size="13" />来源</span></th>
              <th style="min-width:130px"><span class="th-icon"><MapPin :size="13" />所属地区</span></th>
              <th style="min-width:100px">栏目</th>
              <th style="min-width:110px">处理Stage</th>
              <th style="min-width:150px"><span class="th-icon"><Timer :size="13" />队列 / ETA</span></th>
              <th style="min-width:130px">所属机构</th>
              <th style="min-width:100px">状态</th>
              <th style="min-width:120px">进度</th>
              <th style="min-width:150px">更新时间</th>
              <th style="min-width:100px">操作</th>
            </tr>
          </thead>
          <tbody v-if="!loading && !error">
            <tr v-for="d in filtered" :key="d.id" :class="{ 'processing-row': isProcessing(d) }">
              <td>
                <div class="material-title">
                  <component :is="sourceIcon(d)" :size="18" />
                  <span><b>{{ d.title }}</b
                  ><small>{{ sourceLabel(d) }} · {{ d.source_type === "WEB_ARTICLE" ? formatDisplayDate(d.original_published_at) : (d.file_name || "尚未上传文件") }}</small></span>
                </div>
              </td>
              <td>
                <b class="source-cell">{{ d.source_name || "—" }}</b>
                <small v-if="d.source_type">{{ sourceLabel(d) }}</small>
              </td>
              <td>
                <b>{{ d.region_display || d.region_code || "—" }}</b>
                <small v-if="d.region_code">编码 {{ d.region_code }}</small>
              </td>
              <td>
                <span class="channel-tag">{{ channelLabel(d.publish_channel || d.category) }}</span>
              </td>
              <td>
                <span v-if="d.stage" class="stage-tag"><Zap :size="11" />{{ stageLabel(d.stage) }}</span>
                <span v-else class="stage-tag muted">未开始</span>
              </td>
              <td>
                <span class="eta-cell">{{ estimateEtaMinutes(d.queue_position, d.stage) }}</span>
              </td>
              <td>{{ d.organization_name }}</td>
              <td>
                <StatusTag
                  :status="d.status"
                  :text="statusLabel(d.status)"
                />
              </td>
              <td>
                <div class="progress">
                  <i :style="{ width: displayProgress(d) + '%' }"></i>
                </div>
                <small>{{ displayProgress(d) }}%</small>
              </td>
              <td>{{ formatDisplayDateTime(d.updated_at) }}</td>
              <td>
                <RouterLink
                  :to="processHref(d)"
                  :class="['row-action', { 'primary-action': isProcessing(d) }]"
                >
                  <Zap v-if="isProcessing(d)" :size="13" />
                  {{ d.status === 'WAITING_REVIEW' ? '审核' : (isProcessing(d) ? '查看处理' : '查看') }}
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="loading" class="empty-state">正在加载材料…</div>

      <div v-else-if="error" class="empty-state error-state"><b>材料暂时无法读取</b><p>{{ error }}</p></div>
      <div v-else-if="!filtered.length" class="empty-state"><b>没有符合条件的材料</b><p>请调整关键词或处理状态后再试。</p></div>
      <div class="pagination">
        <span>共 {{ filtered.length }} 条</span>
        <div>
          <button disabled>上一页</button><button class="current">1</button
          ><button disabled>下一页</button>
        </div>
      </div>
    </section>
    <div v-if="showAddContent" class="content-add-mask" @click.self="showAddContent = false">
      <section class="content-add-dialog" role="dialog" aria-modal="true" aria-labelledby="add-content-title">
        <header><div><h2 id="add-content-title">添加内容</h2><p>选择最符合当前材料的方式，后续都进入同一审核与发布流程。</p></div><button type="button" aria-label="关闭添加内容" @click="showAddContent = false"><X /></button></header>
        <div class="content-add-options">
          <RouterLink to="/documents/upload" @click="showAddContent = false"><Upload /><span><b>上传 PDF / 图片</b><small>上传通知文件，系统自动识别正文和关键信息。</small></span></RouterLink>
          <RouterLink v-if="isPlatformAdmin()" :to="{ path: '/public-import', query: { mode: 'web' } }" @click="showAddContent = false"><Globe2 /><span><b>粘贴网页链接</b><small>导入政府或公共机构的公开文章。</small></span></RouterLink>
          <RouterLink v-if="isPlatformAdmin()" :to="{ path: '/public-import', query: { mode: 'manual' } }" @click="showAddContent = false"><PenLine /><span><b>手工录入</b><small>用于纸质通知或暂时无法抓取的公开内容。</small></span></RouterLink>
        </div>
        <p v-if="!isPlatformAdmin()" class="content-add-note">网页链接和手工录入由平台管理员维护，当前账号可上传 PDF 或图片。</p>
      </section>
    </div>
  </div>
</template>
<style scoped>
.content-updated { margin: -12px 0 18px; color: var(--color-muted); font-size: 12px; }
.content-updated.import-filter { padding: 12px 16px; background: var(--color-primary-soft); border-radius: 8px; color: var(--color-primary); }
.content-updated.import-filter a { margin-left: 10px; font-weight: 700; }
.th-icon { display: inline-flex; align-items: center; gap: 5px; }
.table-wrap { overflow-x: auto; }
.data-table.dense th, .data-table.dense td { padding: 11px 14px; font-size: 12.5px; }
.source-cell { color: var(--color-ink); font-size: 12.5px; }
.channel-tag { display: inline-block; padding: 3px 9px; border-radius: 5px; background: var(--color-primary-soft); color: var(--color-primary); font-size: 11.5px; font-weight: 600; }
.stage-tag { display: inline-flex; align-items: center; gap: 5px; padding: 3px 9px; border-radius: 5px; background: #FBEFE1; color: #B56518; font-size: 11.5px; font-weight: 600; }
.stage-tag.muted { background: #F0F2F1; color: var(--color-muted); }
.eta-cell { color: var(--color-primary); font-weight: 600; font-size: 12px; font-variant-numeric: tabular-nums; }
.row-action { display: inline-flex; align-items: center; gap: 5px; padding: 6px 11px; border-radius: 6px; background: var(--color-surface); border: 1px solid var(--color-border); color: var(--color-text); font-size: 12px; font-weight: 600; cursor: pointer; text-decoration: none; }
.row-action:hover { border-color: var(--color-primary); color: var(--color-primary); }
.row-action.primary-action { background: var(--color-primary); color: #fff; border-color: var(--color-primary); box-shadow: 0 1px 3px rgba(14,90,85,.22); }
.row-action.primary-action:hover { background: var(--color-primary-dark); color: #fff; }
.material-title small { margin-top: 5px; display: block; }
</style>
