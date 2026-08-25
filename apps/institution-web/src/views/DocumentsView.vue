<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { Search, Plus, Upload, Globe2, FileImage, FileText, PenLine, X, RefreshCw } from "lucide-vue-next";
import { formatDisplayDate, formatDisplayDateTime, statusLabel } from "../utils/display";
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
            placeholder="搜索材料标题或文件名"
          />
        </div>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>材料</th>
            <th>所属机构</th>
            <th>处理状态</th>
            <th>进度</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody v-if="!loading && !error">
          <tr v-for="d in filtered" :key="d.id">
            <td>
              <div class="material-title">
                <component :is="sourceIcon(d)" :size="18" />
                <span><b>{{ d.title }}</b
                ><small>{{ sourceLabel(d) }} · {{ d.source_type === "WEB_ARTICLE" ? [d.source_name, d.category, formatDisplayDate(d.original_published_at)].filter(Boolean).join(" · ") : d.file_name || "尚未上传文件" }}</small></span>
              </div>
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
                :to="
                  d.status === 'WAITING_REVIEW'
                    ? `/documents/${d.id}/review`
                    : `/documents/${d.id}/process`
                "
                >查看</RouterLink
              >
            </td>
          </tr>
        </tbody>
      </table>

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
