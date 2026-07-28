<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import { Search } from "lucide-vue-next";
import { apiMessage, http, type ApiResponse } from "../api/http";
import { formatDisplayDateTime } from "../utils/display";

interface OperationLog {
  id: number;
  operator_name: string;
  action: string;
  target_type: string;
  target_id: number;
  result: string;
  created_at: string;
}

const logs = ref<OperationLog[]>([]);
const loading = ref(true);
const error = ref("");
const query = ref("");
const date = ref("");
const actionText: Record<string, string> = {
  REVIEW: "提交审核",
  PUBLISH: "发布内容",
  WITHDRAW: "撤回内容",
  IMPORT: "导入公开信息",
  PROCESS: "发起 AI 处理",
};
const filtered = computed(() =>
  logs.value.filter((log) => {
    const text = `${log.operator_name}${log.action}${log.target_type}${log.target_id}`;
    return (
      (!query.value.trim() || text.includes(query.value.trim())) &&
      (!date.value || String(log.created_at).startsWith(date.value))
    );
  }),
);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const response =
      await http.get<ApiResponse<OperationLog[]>>("/operation-logs");
    logs.value = response.data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader
      title="操作日志"
      description="追踪关键业务操作，记录操作人、对象和结果。"
    />
    <section class="panel">
      <div class="filters">
        <label class="search">
          <Search /><input v-model="query" aria-label="搜索操作人或对象" placeholder="搜索操作人或对象" />
        </label>
        <input v-model="date" aria-label="按操作日期筛选" type="date" />
      </div>
      <div v-if="loading" class="empty-state">正在加载操作日志…</div>
      <div v-else-if="error" class="empty-state">
        <p>{{ error }}</p>
        <button type="button" class="btn secondary" @click="load">重新加载</button>
      </div>
      <table v-else-if="filtered.length">
        <thead>
          <tr>
            <th>操作人</th>
            <th>操作</th>
            <th>对象</th>
            <th>结果</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in filtered" :key="log.id">
            <td><b>{{ log.operator_name }}</b></td>
            <td>{{ actionText[log.action] || log.action }}</td>
            <td>{{ log.target_type }} #{{ log.target_id }}</td>
            <td><span class="success-text">● {{ log.result }}</span></td>
            <td>{{ formatDisplayDateTime(log.created_at) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">没有符合当前条件的操作日志。</div>
    </section>
  </div>
</template>
