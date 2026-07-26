<script setup lang="ts">
import { onMounted, ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { buildH5GuideUrl } from "../utils/h5-url";
import { ExternalLink, RotateCcw, Search } from "lucide-vue-next";

const rows = ref<DocumentRow[]>([]);
const loading = ref(true);
const error = ref("");
const query = ref("");
const withdrawingId = ref<number | null>(null);

function h5GuideUrl(documentId: number): string {
  return buildH5GuideUrl(`guide-${documentId}`, {
    configuredBaseUrl: import.meta.env.VITE_H5_BASE_URL,
    isDev: import.meta.env.DEV,
    protocol: window.location.protocol,
    hostname: window.location.hostname,
  });
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const response = await documentApi.list();
    rows.value = response.data.data.filter(
      (item) => item.status === "PUBLISHED",
    );
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function withdraw(row: DocumentRow) {
  if (!window.confirm(`确认撤回“${row.title}”吗？撤回后用户端将无法访问。`))
    return;
  withdrawingId.value = row.id;
  error.value = "";
  try {
    await documentApi.withdraw(row.id);
    await load();
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    withdrawingId.value = null;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader
      title="已发布内容"
      description="管理用户端正在展示的指南和权威资讯。"
    />
    <section class="panel">
      <div class="filters">
        <div class="search">
          <Search /><input v-model="query" placeholder="搜索标题" />
        </div>
        <button class="btn secondary" @click="load">
          <RotateCcw :size="16" />刷新
        </button>
      </div>
      <p v-if="error" class="form-error">{{ error }}</p>
      <table>
        <thead>
          <tr>
            <th>标题</th>
            <th>机构</th>
            <th>发布日期</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows.filter((item) => item.title.includes(query))"
            :key="row.id"
          >
            <td>
              <b>{{ row.title }}</b>
            </td>
            <td>{{ row.organization_name }}</td>
            <td>{{ String(row.updated_at).slice(0, 10) }}</td>
            <td><StatusTag status="PUBLISHED" text="已发布" /></td>
            <td>
              <a
                :href="h5GuideUrl(row.id)"
                target="_blank"
                rel="noreferrer"
                ><ExternalLink :size="14" />查看</a
              >
              ·
              <button
                class="text-action danger"
                :disabled="withdrawingId === row.id"
                @click="withdraw(row)"
              >
                {{ withdrawingId === row.id ? "撤回中…" : "撤回" }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">正在加载已发布内容…</div>
      <div v-else-if="rows.length === 0" class="empty-state">
        暂无已发布内容。
      </div>
    </section>
  </div>
</template>
