<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { Upload, ArrowRight, FileClock, CircleCheck, BookOpen, TriangleAlert, Globe2, FileImage, FileText } from "lucide-vue-next";
const rows = ref<DocumentRow[]>([]);
const loading = ref(true);
const error = ref("");
const waiting = computed(() => rows.value.filter((row) => row.status === "WAITING_REVIEW"));
const processing = computed(() => rows.value.filter((row) => ["UPLOADED", "PROCESSING"].includes(row.status)));
const published = computed(() => rows.value.filter((row) => row.status === "PUBLISHED"));
const failed = computed(() => rows.value.filter((row) => row.status === "FAILED"));
const recent = computed(() => rows.value.slice(0, 5));
const statusText: Record<string,string> = { UPLOADED:"待处理", PROCESSING:"处理中", WAITING_REVIEW:"待审核", REVIEWED:"已审核", PUBLISHED:"已发布", FAILED:"失败", WITHDRAWN:"已撤回" };
function sourceIcon(row: DocumentRow) {
  return row.source_type === "WEB_ARTICLE" ? Globe2 : row.source_type === "IMAGE" ? FileImage : FileText;
}
function sourceDescription(row: DocumentRow) {
  if (row.source_type === "WEB_ARTICLE") {
    return [row.source_name || "权威网页", row.category, row.original_published_at?.slice(0, 10)]
      .filter(Boolean).join(" · ");
  }
  return row.file_name || "上传材料";
}
onMounted(async () => { try { rows.value = (await documentApi.list()).data.data; } catch (cause) { error.value = apiMessage(cause); } finally { loading.value = false; } });
</script>
<template>
  <div>
    <PageHeader title="内容工作台" description="查看真实材料状态，继续今天需要处理的公共服务内容。">
      <RouterLink to="/documents/upload" class="btn primary"><Upload :size="17" />上传新材料</RouterLink>
    </PageHeader>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="metric-strip" aria-label="材料概览">
      <article><span class="metric-icon blue"><FileClock /></span><div><small>处理中</small><strong>{{ loading ? "—" : processing.length }}</strong><em>已上传或正在提取</em></div></article>
      <article><span class="metric-icon orange"><TriangleAlert /></span><div><small>等待审核</small><strong>{{ loading ? "—" : waiting.length }}</strong><em>需要人工逐项确认</em></div></article>
      <article><span class="metric-icon green"><CircleCheck /></span><div><small>正在公开</small><strong>{{ loading ? "—" : published.length }}</strong><em>用户端当前可查看</em></div></article>
      <article><span class="metric-icon teal"><BookOpen /></span><div><small>处理异常</small><strong>{{ loading ? "—" : failed.length }}</strong><em>需要检查服务日志</em></div></article>
    </section>
    <div class="dashboard-grid">
      <section class="panel">
        <div class="panel-title"><div><h2>近期材料</h2><p>按当前真实处理状态继续下一步</p></div><RouterLink to="/documents">查看全部 <ArrowRight :size="16" /></RouterLink></div>
        <table v-if="recent.length"><thead><tr><th>材料名称</th><th>状态</th><th>更新时间</th><th></th></tr></thead><tbody><tr v-for="row in recent" :key="row.id"><td><div class="material-title"><component :is="sourceIcon(row)" :size="18"/><span><b>{{ row.title }}</b><small>{{ sourceDescription(row) }}</small></span></div></td><td><StatusTag :status="row.status" :text="statusText[row.status] || row.status" /></td><td>{{ row.updated_at }}</td><td><RouterLink :to="row.status === 'WAITING_REVIEW' ? `/documents/${row.id}/review` : `/documents/${row.id}/process`">查看详情</RouterLink></td></tr></tbody></table>
        <div v-else-if="loading" class="empty-state">正在加载工作台…</div>
        <div v-else class="empty-state"><b>还没有材料</b><p>上传第一份材料后，处理进度会显示在这里。</p></div>
      </section>
      <aside class="panel todo">
        <div class="panel-title"><div><h2>当前待办</h2><p>基于真实状态生成</p></div></div>
        <ol v-if="waiting.length"><li v-for="(row,index) in waiting.slice(0,7)" :key="row.id"><span>{{ index + 1 }}</span><div><b>{{ row.title }}</b><small>{{ row.source_type === "WEB_ARTICLE" ? `${row.source_name || "网页文章"} · 等待核对来源、封面和正文` : "结构化结果已生成，等待人工对照审核" }}</small></div></li></ol>
        <div v-else class="empty-state compact"><CircleCheck /><b>当前没有待审核材料</b><p>新的处理结果会自动出现在这里。</p></div>
      </aside>
    </div>
  </div>
</template>
