<script setup lang="ts">
import { onMounted, ref } from "vue";
import { BadgeCheck, CreditCard, Handshake, ReceiptText, Users } from "lucide-vue-next";
import PageHeader from "../components/PageHeader.vue";
import { http, apiMessage } from "../api/http";

interface Overview {
  plans: number;
  activeSubscriptions: number;
  membershipPlans: number;
  activeMembers: number;
  newMembersThisMonth: number;
  verifiedProviders: number;
  activeProducts: number;
  ordersThisMonth: number;
  pendingRefunds: number;
  activeSponsors: number;
  payment: { available: boolean; provider: string; message: string };
}

const data = ref<Overview | null>(null);
const error = ref("");
const metrics = [
  ["activeSubscriptions", "有效机构授权", Users],
  ["activeMembers", "有效会员", BadgeCheck],
  ["newMembersThisMonth", "本月新增会员", Users],
  ["membershipPlans", "会员套餐", CreditCard],
  ["verifiedProviders", "已核验服务商", BadgeCheck],
  ["activeProducts", "合作服务", Handshake],
  ["ordersThisMonth", "本月订单", ReceiptText],
  ["pendingRefunds", "待处理退款", CreditCard],
  ["activeSponsors", "活动合作位", Handshake],
] as const;

onMounted(async () => {
  try {
    data.value = (await http.get("/commercial/overview")).data.data;
  } catch (cause) {
    error.value = apiMessage(cause);
  }
});
</script>

<template>
  <main>
    <PageHeader title="商业运营" description="管理机构授权、居民会员、可信服务与合作项目。" />
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section v-if="data" class="panel commercial-metrics">
      <article v-for="item in metrics" :key="item[0]">
        <component :is="item[2]" />
        <small>{{ item[1] }}</small>
        <strong>{{ data[item[0]] }}</strong>
      </article>
    </section>
    <section v-if="data" class="panel payment-boundary">
      <CreditCard />
      <div>
        <h2>支付配置</h2>
        <p>线上支付：{{ data.payment.available ? "已配置" : "未配置" }}</p>
        <p>支付宝：未接入　微信支付：未接入</p>
        <details>
          <summary>查看接入说明</summary>
          <p>当前仅开放课堂演示支付。正式接入前需完成商户签约、回调验签和退款流程验收。</p>
        </details>
      </div>
    </section>
    <section v-if="data && !data.verifiedProviders" class="panel commercial-note">
      <h2>开始运营可信服务</h2>
      <p>还没有通过核验的服务商。完成资质、服务范围和退款规则核对后，合作服务才会对居民展示。</p>
      <p class="next-action">下一步：由平台运营人员登记服务商资质并提交核验。</p>
    </section>
    <section class="panel commercial-note">
      <h2>运营分区</h2>
      <div class="operation-sections">
        <span>机构订阅</span><span>会员套餐</span><span>会员概览</span><span>服务商</span>
        <span>合作服务</span><span>公益与赞助位</span><span>订单与退款</span><span>支付配置</span>
      </div>
    </section>
  </main>
</template>

<style scoped>
.commercial-metrics{display:grid;grid-template-columns:repeat(3,1fr);margin-bottom:20px}.commercial-metrics article{display:grid;grid-template-columns:auto 1fr;gap:5px 10px;padding:20px;border-right:1px solid var(--color-border);border-bottom:1px solid var(--color-border)}.commercial-metrics svg{grid-row:1/3;color:var(--color-primary)}.commercial-metrics small{color:var(--color-muted)}.commercial-metrics strong{font-size:24px}.payment-boundary{display:flex;gap:15px;padding:22px;margin-bottom:20px}.payment-boundary h2,.payment-boundary p{margin:0 0 7px}.payment-boundary details{margin-top:10px}.payment-boundary summary{cursor:pointer;color:var(--color-primary);font-weight:700}.commercial-note{padding:22px;margin-bottom:20px}.next-action{color:var(--color-primary);font-weight:700}.operation-sections{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.operation-sections span{display:flex;align-items:center;min-height:52px;padding:0 16px;border:1px solid var(--color-border);background:#fff;color:#254840;font-weight:700}@media(max-width:900px){.commercial-metrics,.operation-sections{grid-template-columns:repeat(2,1fr)}}@media(max-width:560px){.commercial-metrics,.operation-sections{grid-template-columns:1fr}}
</style>
