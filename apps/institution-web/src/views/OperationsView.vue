<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Activity, AlertTriangle, Bot, Images, RefreshCw } from "lucide-vue-next";
import PageHeader from "../components/PageHeader.vue";
import { apiMessage } from "../api/http";
import { operationMetricsApi, type OperationMetrics } from "../api/operations";
import { formatDisplayDateTime, statusLabel } from "../utils/display";

const metrics = ref<OperationMetrics | null>(null);
const loading = ref(false);
const error = ref("");

const todayCards = computed(() => metrics.value ? [
  ["今日发现", metrics.value.todayDiscoveredCount],
  ["今日采集", metrics.value.todayCollectedCount],
  ["今日重复", metrics.value.todayDuplicateCount],
  ["今日失败", metrics.value.todayFailedCount],
  ["内容待审核", metrics.value.waitingReviewCount],
  ["已发布", metrics.value.publishedCount],
  ["图片待审核", metrics.value.pendingImageCandidateCount],
  ["AI Token", `${metrics.value.tokenUsedToday.toLocaleString()} / ${metrics.value.tokenBudgetTotal.toLocaleString()}`],
] : []);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    metrics.value = (await operationMetricsApi.current()).data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}

function duration(value: number) {
  if (!value) return "暂无样本";
  return value < 1000 ? `${value} ms` : `${Math.round(value / 100) / 10} 秒`;
}

onMounted(load);
</script>

<template>
  <div>
    <PageHeader title="平台运营看板" description="查看真实采集、AI预算、图片审核和失败来源，不使用估算或随机数据。">
      <button class="btn secondary" type="button" :disabled="loading" @click="load">
        <RefreshCw :size="17" />{{ loading ? "正在刷新…" : "刷新数据" }}
      </button>
    </PageHeader>
    <p v-if="error" class="inline-error">{{ error }}</p>

    <section v-if="metrics" class="metric-strip operations-summary" aria-label="今日运营摘要">
      <article v-for="card in todayCards" :key="String(card[0])">
        <span class="metric-icon blue"><Activity /></span>
        <div><small>{{ card[0] }}</small><strong>{{ card[1] }}</strong></div>
      </article>
    </section>

    <div v-if="metrics" class="dashboard-grid operations-grid">
      <section class="panel">
        <div class="panel-title"><div><h2>来源运行状态</h2><p>启停、最近采集结果、下次运行和连续失败次数。</p></div></div>
        <table class="data-table">
          <thead><tr><th>来源</th><th>启停</th><th>最近状态</th><th>时间</th><th>错误</th></tr></thead>
          <tbody>
            <tr v-for="source in metrics.sources" :key="source.id">
              <td><b>{{ source.source_name }}</b><small>{{ source.domain }}</small></td>
              <td>{{ source.enabled ? "已启用" : "已停用" }}</td>
              <td>{{ statusLabel(source.last_status) }}<small>连续失败 {{ source.failure_count || 0 }} 次</small></td>
              <td>{{ formatDisplayDateTime(source.last_crawled_at) }}<small>下次 {{ formatDisplayDateTime(source.next_run_at) }}</small></td>
              <td>{{ source.last_error || "无" }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!metrics.sources.length" class="empty-state">当前没有权威来源配置。</div>
      </section>

      <aside class="panel">
        <div class="panel-title"><div><h2>处理效率</h2><p>只统计数据库中已有完成样本。</p></div></div>
        <div class="operations-efficiency">
          <article><Activity /><span>平均采集耗时<b>{{ duration(metrics.averageCrawlMs) }}</b></span></article>
          <article><Bot /><span>平均 AI 耗时<b>{{ duration(metrics.averageAiMs) }}</b></span></article>
          <article><Images /><span>图片候选待审核<b>{{ metrics.pendingImageCandidateCount }} 项</b></span></article>
          <article><AlertTriangle /><span>最近未解决错误<b>{{ metrics.recentErrors.length }} 项</b></span></article>
        </div>
      </aside>
    </div>

    <section v-if="metrics" class="panel">
      <div class="panel-title"><div><h2>AI 队列与 Token 预算</h2><p>按真实队列状态汇总，等待审批与等待预算不会被计为已执行。</p></div></div>
      <table class="data-table">
        <thead><tr><th>队列状态</th><th>任务数</th><th>预计 Token</th><th>实际 Token</th></tr></thead>
        <tbody>
          <tr v-for="item in metrics.aiQueueByStatus" :key="item.status">
            <td>{{ statusLabel(item.status) }}</td>
            <td>{{ item.item_count }}</td>
            <td>{{ item.estimated_tokens.toLocaleString() }}</td>
            <td>{{ item.actual_tokens.toLocaleString() }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!metrics.aiQueueByStatus.length" class="empty-state">当前没有 AI 队列任务。</div>
    </section>

    <section v-if="metrics" class="panel">
      <div class="panel-title"><div><h2>最近错误与失败来源</h2><p>仅显示未解决错误摘要，不展示内部堆栈或敏感请求信息。</p></div></div>
      <table class="data-table">
        <thead><tr><th>来源 / 阶段</th><th>错误摘要</th><th>重试</th><th>发生时间</th></tr></thead>
        <tbody>
          <tr v-for="item in metrics.recentErrors" :key="item.id">
            <td><b>{{ item.source_name }}</b><small>{{ item.processing_stage }} · {{ item.error_code }}</small></td>
            <td>{{ item.error_summary }}<small>{{ item.failed_url || "无单独 URL" }}</small></td>
            <td>{{ item.retryable ? `可重试，已重试 ${item.retry_count} 次` : "不可重试" }}</td>
            <td>{{ formatDisplayDateTime(item.created_at) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!metrics.recentErrors.length" class="empty-state">当前没有未解决的采集错误。</div>
    </section>
    <div v-if="loading && !metrics" class="empty-state">正在读取平台运营数据…</div>
  </div>
</template>
