<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { Search, Upload, SlidersHorizontal } from "lucide-vue-next";
const query = ref("");
const status = ref("全部状态");
const loading = ref(true);
const error = ref("");
const documents = ref<DocumentRow[]>([]);
const statusText: Record<string, string> = {
  UPLOADED: "待处理",
  PROCESSING: "处理中",
  WAITING_REVIEW: "待审核",
  REVIEWED: "已审核",
  PUBLISHED: "已发布",
  FAILED: "失败",
  WITHDRAWN: "已撤回",
};
const filtered = computed(() =>
  documents.value.filter(
    (d) =>
      (!query.value || d.title.includes(query.value)) &&
      (status.value === "全部状态" || statusText[d.status] === status.value),
  ),
);
onMounted(async () => {
  try {
    documents.value = (await documentApi.list()).data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
});
</script>
<template>
  <div>
    <PageHeader
      title="材料管理"
      description="统一管理机构上传材料及其 AI 处理、审核和发布状态。"
      ><RouterLink class="btn primary" to="/documents/upload"
        ><Upload :size="17" />上传材料</RouterLink
      ></PageHeader
    >
    <section class="panel">
      <div class="filters">
        <div class="search">
          <Search :size="18" /><input
            v-model="query"
            placeholder="搜索材料标题或文件名"
          />
        </div>
        <select v-model="status">
          <option>全部状态</option>
          <option>处理中</option>
          <option>待审核</option>
          <option>已发布</option>
          <option>失败</option>
          <option>已撤回</option></select
        ><button class="btn secondary">
          <SlidersHorizontal :size="17" />更多筛选
        </button>
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
              <b>{{ d.title }}</b
              ><small>{{ d.file_name || "尚未上传文件" }}</small>
            </td>
            <td>{{ d.organization_name }}</td>
            <td>
              <StatusTag
                :status="d.status"
                :text="statusText[d.status] || d.status"
              />
            </td>
            <td>
              <div class="progress">
                <i :style="{ width: d.progress + '%' }"></i>
              </div>
              <small>{{ d.progress }}%</small>
            </td>
            <td>{{ d.updated_at }}</td>
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

      <div v-else-if="error" class="empty-state error-state">{{ error }}</div>
      <div class="pagination">
        <span>共 {{ filtered.length }} 条</span>
        <div>
          <button disabled>上一页</button><button class="current">1</button
          ><button disabled>下一页</button>
        </div>
      </div>
    </section>
  </div>
</template>
