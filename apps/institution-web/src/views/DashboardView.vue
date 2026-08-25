<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { Plus, ArrowRight, CircleCheck, TriangleAlert, Globe2, FileImage, FileText } from "lucide-vue-next";
import { formatDisplayDate, formatDisplayDateTime } from "../utils/display";
import { currentUser, isPlatformAdmin } from "../auth";

const rows = ref<DocumentRow[]>([]);
const loading = ref(true);
const error = ref("");
const user = currentUser();
const waiting = computed(() => rows.value.filter((row) => row.status === "WAITING_REVIEW"));
const readyToPublish = computed(() => rows.value.filter((row) => row.status === "REVIEWED"));
const failed = computed(() => rows.value.filter((row) => row.status === "FAILED"));
const todoItems = computed(() => [
  ...failed.value.map((row) => ({ row, reason: "处理没有完成，原文件仍已保留。", action: "查看并重试", priority: "需要关注" })),
  ...waiting.value.map((row) => ({ row, reason: row.source_type === "WEB_ARTICLE" ? "请核对来源、封面和正文依据。" : "AI 结果已生成，请对照原文确认。", action: "开始审核", priority: "待审核" })),
  ...readyToPublish.value.map((row) => ({ row, reason: "审核已完成，请预览用户端效果后发布。", action: "预览并发布", priority: "待发布" })),
]);

function sourceIcon(row: DocumentRow) {
  return row.source_type === "WEB_ARTICLE" ? Globe2 : row.source_type === "IMAGE" ? FileImage : FileText;
}
function sourceDescription(row: DocumentRow) {
  if (row.source_type === "WEB_ARTICLE") {
    return [row.source_name || "权威网页", row.category, formatDisplayDate(row.original_published_at)]
      .filter(Boolean).join(" · ");
  }
  return row.file_name || "上传材料";
}
function todoPath(item: (typeof todoItems.value)[number]) {
  if (item.row.status === "WAITING_REVIEW") return `/documents/${item.row.id}/review`;
  if (item.row.status === "REVIEWED") return `/documents/${item.row.id}/publish`;
  return `/documents/${item.row.id}/process`;
}

onMounted(async () => {
  try { rows.value = (await documentApi.list()).data.data; }
  catch (cause) { error.value = apiMessage(cause); }
  finally { loading.value = false; }
});
</script>

<template>
  <div>
    <PageHeader :title="`早上好，${user?.displayName || '老师'}`" :description="loading ? '正在整理今天的工作…' : `今天有 ${todoItems.length} 件内容需要处理。`">
      <RouterLink to="/documents" class="btn primary"><Plus :size="17" />添加内容</RouterLink>
    </PageHeader>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="metric-strip dashboard-actions" aria-label="今日工作概览">
      <RouterLink :to="{ path: '/documents', query: { status: 'WAITING_REVIEW' } }"><span class="metric-icon orange"><TriangleAlert /></span><div><small>待审核</small><strong>{{ loading ? "—" : waiting.length }}</strong><em>对照原文确认关键信息</em></div><ArrowRight /></RouterLink>
      <RouterLink :to="{ path: '/documents', query: { status: 'WAITING_PUBLISH' } }"><span class="metric-icon green"><CircleCheck /></span><div><small>待发布</small><strong>{{ loading ? "—" : readyToPublish.length }}</strong><em>预览用户端后确认发布</em></div><ArrowRight /></RouterLink>
      <RouterLink :to="{ path: '/documents', query: { status: 'EXCEPTION' } }"><span class="metric-icon teal"><TriangleAlert /></span><div><small>需要关注</small><strong>{{ loading ? "—" : failed.length }}</strong><em>查看原因并重新处理</em></div><ArrowRight /></RouterLink>
    </section>
    <section class="panel today-todos">
      <div class="panel-title"><div><h2>今日待办</h2><p>按需要关注、待审核、待发布的顺序排列。</p></div><RouterLink to="/documents">查看全部 <ArrowRight :size="16" /></RouterLink></div>
      <div v-if="loading" class="empty-state">正在加载今日待办…</div>
      <ol v-else-if="todoItems.length">
        <li v-for="item in todoItems.slice(0, 8)" :key="item.row.id">
          <component :is="sourceIcon(item.row)" />
          <div><span>{{ item.priority }}</span><b>{{ item.row.title }}</b><small>{{ sourceDescription(item.row) }}</small><p>{{ item.reason }}</p></div>
          <time>{{ formatDisplayDateTime(item.row.updated_at) }}</time>
          <RouterLink class="btn secondary" :to="todoPath(item)">{{ item.action }}</RouterLink>
        </li>
      </ol>
      <div v-else class="empty-state compact"><CircleCheck /><b>今天的待办已经处理完了</b><p>有新的处理结果时，会自动出现在这里。</p></div>
    </section>
    <section v-if="isPlatformAdmin()" class="dashboard-secondary-links"><RouterLink to="/public-sources">查看采集与来源<ArrowRight /></RouterLink><RouterLink to="/operations">查看数据概览<ArrowRight /></RouterLink></section>
  </div>
</template>
