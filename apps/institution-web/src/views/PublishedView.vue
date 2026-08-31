<script setup lang="ts">
import { onMounted, ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documentApi, type DocumentRow } from "../api/documents";
import { apiMessage } from "../api/http";
import { buildH5GuideUrl } from "../utils/h5-url";
import { ExternalLink, RotateCcw, Search, Shuffle } from "lucide-vue-next";
import { formatDisplayDate } from "../utils/display";

const rows = ref<DocumentRow[]>([]);
const loading = ref(true);
const error = ref("");
const query = ref("");
const withdrawingId = ref<number | null>(null);
const adjustingId = ref<number | null>(null);
const savingChannelId = ref<number | null>(null);

const channelOptions = [
  { value: "HEALTH", label: "健康" },
  { value: "ELDERLY", label: "养老" },
  { value: "MEALS", label: "助餐" },
  { value: "SERVICES", label: "办事" },
  { value: "FRAUD", label: "防诈" },
  { value: "ACTIVITY", label: "活动" },
  { value: "COMMUNITY", label: "社区" },
] as const;

function channelLabel(value?: string): string {
  return channelOptions.find((item) => item.value === value)?.label || "社区";
}

function h5GuideUrl(documentId: number): string {
  const row = rows.value.find((item) => item.id === documentId);
  return buildH5GuideUrl(`${row?.source_type === "WEB_ARTICLE" ? "news" : "guide"}-${documentId}`, {
    configuredBaseUrl: import.meta.env.VITE_H5_BASE_URL,
    isDev: import.meta.env.DEV,
    protocol: window.location.protocol,
    hostname: window.location.hostname,
  }, row?.source_type === "WEB_ARTICLE" ? "news" : "guide", row?.region_code);
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

async function adjustChannel(row: DocumentRow, channel: string) {
  if (channel === row.publish_channel) {
    adjustingId.value = null;
    return;
  }
  savingChannelId.value = row.id;
  error.value = "";
  try {
    await documentApi.updatePublicationChannel(
      row.id,
      channel as NonNullable<DocumentRow["publish_channel"]>,
    );
    await load();
    adjustingId.value = null;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    savingChannelId.value = null;
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
            <th>栏目</th>
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
            <td><span class="channel-tag">{{ channelLabel(row.publish_channel) }}</span></td>
            <td>{{ formatDisplayDate(row.updated_at) }}</td>
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
                class="text-action"
                :disabled="savingChannelId === row.id"
                @click="adjustingId = adjustingId === row.id ? null : row.id"
              >
                <Shuffle :size="14" />调整栏目
              </button>
              ·
              <button
                class="text-action danger"
                :disabled="withdrawingId === row.id"
                @click="withdraw(row)"
              >
                {{ withdrawingId === row.id ? "撤回中…" : "撤回" }}
              </button>
              <div v-if="adjustingId === row.id" class="channel-picker">
                <button
                  v-for="option in channelOptions"
                  :key="option.value"
                  class="channel-option"
                  :class="{ active: option.value === row.publish_channel }"
                  :disabled="savingChannelId === row.id"
                  @click="adjustChannel(row, option.value)"
                >
                  {{ option.label
                  }}{{ option.value === row.publish_channel ? "（当前）" : "" }}
                </button>
              </div>
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

<style scoped>
.channel-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 12px;
  background: #eef4f3;
  color: #173f3a;
  font-size: 13px;
  white-space: nowrap;
}
.channel-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  padding: 8px;
  border: 1px solid #e3e9e8;
  border-radius: 8px;
  background: #fbfdfc;
}
.channel-option {
  padding: 4px 12px;
  border: 1px solid #d6e0de;
  border-radius: 14px;
  background: #fff;
  color: #2d5a55;
  font-size: 13px;
  cursor: pointer;
}
.channel-option:hover:not(:disabled) {
  border-color: #173f3a;
}
.channel-option.active {
  background: #173f3a;
  color: #fff;
  border-color: #173f3a;
}
.channel-option:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
