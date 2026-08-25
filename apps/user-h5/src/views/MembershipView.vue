<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import QRCode from "qrcode";
import { siAlipay, siWechat } from "simple-icons";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { cancelPayment, createPaymentSession, fetchMembershipCapabilities, fetchMembershipMe,
  fetchMembershipPlans, getPaymentStatus, type PaymentSession, type MembershipPlan } from "../api";
import { Check, ShieldCheck, X } from "lucide-vue-next";

const plans = ref<MembershipPlan[]>([]);
const capabilities = ref({ available: false, provider: "UNCONFIGURED", testEnvironment: false, realPaymentAvailable: false, message: "" });
const membership = ref<Record<string, unknown>>({ active: false });
const selected = ref<MembershipPlan | null>(null);
const session = ref<PaymentSession | null>(null);
const method = ref<"ALIPAY" | "WECHAT">("ALIPAY");
const loading = ref(true); const busy = ref(false); const error = ref(""); const success = ref("");
const qr = ref<HTMLCanvasElement | null>(null);
const now = ref(Date.now());
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let clockTimer: ReturnType<typeof setInterval> | null = null;
const remaining = computed(() => Math.max(0, Math.floor((new Date(session.value?.expiresAt || 0).getTime() - now.value) / 1000)));
const remainingText = computed(() => `${String(Math.floor(remaining.value / 60)).padStart(2, "0")}:${String(remaining.value % 60).padStart(2, "0")}`);
const apiMessage = (cause: unknown) => {
  const error = cause as { response?: { data?: { message?: string } } };
  return error.response?.data?.message || "服务暂时不可用，请稍后重试";
};

async function load() {
  try {
    [plans.value, capabilities.value] = await Promise.all([fetchMembershipPlans(), fetchMembershipCapabilities()]);
    if (localStorage.getItem("jianda_resident_token")) membership.value = await fetchMembershipMe();
  } catch (cause) { error.value = apiMessage(cause); }
  finally { loading.value = false; }
}
async function pay() {
  if (!selected.value) return;
  if (!localStorage.getItem("jianda_resident_token")) { error.value = "请先在“我的”登录居民账号"; return; }
  busy.value = true; error.value = "";
  try {
    session.value = await createPaymentSession(selected.value.id, method.value);
    await nextTick();
    if (qr.value) await QRCode.toCanvas(qr.value, session.value.qrPayload, { width: 232, margin: 2, errorCorrectionLevel: "M" });
    schedulePoll();
  } catch (cause) { error.value = apiMessage(cause); }
  finally { busy.value = false; }
}
function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = setTimeout(pollPayment, 1200);
}
async function pollPayment() {
  if (!session.value || session.value.status !== "PENDING") return;
  try {
    session.value = await getPaymentStatus(session.value.sessionId);
    if (session.value.status === "SUCCESS") {
      success.value = "支付成功，会员权益已生效";
      membership.value = await fetchMembershipMe();
      return;
    }
    if (session.value.status === "PENDING") schedulePoll();
  } catch { schedulePoll(); }
}
async function cancelCurrentPayment() {
  if (!session.value) return;
  busy.value = true;
  try { session.value = await cancelPayment(session.value.sessionId); }
  catch (cause) { error.value = apiMessage(cause); }
  finally { busy.value = false; }
}
onMounted(() => { void load(); clockTimer = setInterval(() => { now.value = Date.now(); }, 1000); });
onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer); if (clockTimer) clearInterval(clockTimer); });
</script>

<template><div class="membership-page"><AppTopBar/><main>
  <header class="membership-hero"><ShieldCheck/><div><h1>简达安心会员</h1><p>核心公共服务功能永久免费</p><small>会员仅提供可选增值权益，不影响政策阅读、办事指南和基础助手。</small></div><span v-if="capabilities.testEnvironment" class="test-environment">测试环境</span></header>
  <p v-if="error" class="membership-message error" role="alert">{{ error }}</p><p v-if="success" class="membership-message success" role="status">{{ success }}</p>
  <section v-if="membership.active" class="membership-active"><Check/><span><b>{{ membership.plan_name }}已生效</b><small>有效期至 {{ new Date(String(membership.expires_at)).toLocaleDateString('zh-CN') }}</small></span></section>
  <div v-if="loading" class="membership-loading">正在读取会员套餐…</div>
  <section v-else class="membership-plans"><article v-for="plan in plans" :key="plan.id" :class="{ featured: plan.billing_period === 'YEAR' }"><header><h2>{{ plan.name }}</h2><span v-if="plan.billing_period === 'YEAR'">更省</span></header><p><b>¥{{ (plan.price_cents/100).toFixed(2) }}</b><del v-if="plan.original_price_cents">¥{{ (plan.original_price_cents/100).toFixed(2) }}</del></p><ul><li v-for="benefit in plan.benefits" :key="benefit"><Check/>{{ benefit }}</li></ul><button type="button" @click="selected=plan">选择{{ plan.name }}</button></article></section>
</main>
<div v-if="selected && !session" class="membership-sheet" @click.self="selected=null"><section><button class="sheet-close" aria-label="关闭" @click="selected=null"><X/></button><h2>确认会员套餐</h2><p>{{ selected.name }} <b>¥{{ (selected.price_cents/100).toFixed(2) }}</b></p><p v-if="!capabilities.available" class="payment-notice">{{ capabilities.message }}</p><div class="payment-methods"><button :class="{active:method==='ALIPAY'}" @click="method='ALIPAY'"><svg viewBox="0 0 24 24" :style="{color:'#1677ff'}"><path fill="currentColor" :d="siAlipay.path"/></svg><span>支付宝</span><Check v-if="method==='ALIPAY'"/></button><button :class="{active:method==='WECHAT'}" @click="method='WECHAT'"><svg viewBox="0 0 24 24" :style="{color:'#07c160'}"><path fill="currentColor" :d="siWechat.path"/></svg><span>微信支付</span><Check v-if="method==='WECHAT'"/></button></div><button class="pay-confirm" :disabled="busy || !capabilities.available" @click="pay">{{ busy?'正在创建订单…':'确认支付' }}</button></section></div>
<div v-if="session" class="membership-sheet qr-sheet"><section><button class="sheet-close" aria-label="关闭" @click="session=null;selected=null"><X/></button><small>返回　　简达会员支付</small><h2>{{ session.planName }}　¥{{ (session.amountCents/100).toFixed(2) }}</h2><p class="qr-method"><svg viewBox="0 0 24 24" :style="{color:session.method==='ALIPAY'?'#1677ff':'#07c160'}"><path fill="currentColor" :d="session.method==='ALIPAY'?siAlipay.path:siWechat.path"/></svg>{{ session.method==='ALIPAY'?'支付宝':'微信支付' }}</p><canvas v-show="session.status==='PENDING'" ref="qr"></canvas><p v-if="session.status==='PENDING'">请使用手机扫码支付</p><p class="payment-status" role="status">支付状态：{{ session.status==='PENDING'?'等待支付':session.status==='SUCCESS'?'支付成功':session.status==='CANCELLED'?'已取消':session.status==='EXPIRED'?'已过期':'支付失败' }}</p><small>订单号：{{ session.sessionId }}<br/>剩余时间：{{ remainingText }}</small><button v-if="session.status==='PENDING'" class="cancel-pay" :disabled="busy" @click="cancelCurrentPayment">取消支付</button><button v-else class="pay-confirm" @click="session=null;selected=null">完成</button></section></div>
</div></template>

<style scoped>
.membership-page{min-height:100dvh;background:#f6f3eb;color:#21342f}.membership-page main{max-width:860px;margin:auto;padding:18px 18px 100px}.membership-hero{position:relative;display:flex;gap:18px;padding:28px;border-radius:14px;background:#123b37;color:#fff;box-shadow:0 12px 32px #123b3722}.membership-hero>svg{width:44px;height:44px;color:#d8bd75}.membership-hero h1{margin:0;font-size:30px}.membership-hero p{margin:8px 0 4px;color:#f2dd9d;font-size:19px}.membership-hero small{color:#d9e5e1;line-height:1.6}.test-environment{position:absolute;right:16px;top:14px;padding:4px 7px;border:1px solid #ffffff55;border-radius:4px;color:#d8e5e2;font-size:11px}.membership-plans{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:24px}.membership-plans article{padding:22px;border:1px solid #d9d4c7;border-radius:12px;background:#fff}.membership-plans article.featured{border:2px solid #b4923f}.membership-plans header{display:flex;justify-content:space-between}.membership-plans h2{margin:0}.membership-plans header span{color:#795f1f}.membership-plans p b{font-size:27px}.membership-plans del{margin-left:8px;color:#8a918e}.membership-plans ul{padding:0;list-style:none}.membership-plans li{display:flex;gap:7px;margin:10px 0}.membership-plans li svg{width:18px;color:#176b63}.membership-plans button,.pay-confirm{width:100%;min-height:50px;border:0;border-radius:7px;background:#176b63;color:#fff;font-weight:800}.membership-sheet{position:fixed;inset:0;z-index:60;display:flex;align-items:flex-end;justify-content:center;background:#10292580}.membership-sheet>section{position:relative;width:min(560px,100%);padding:26px 22px calc(24px + env(safe-area-inset-bottom));border-radius:18px 18px 0 0;background:#fff}.sheet-close{position:absolute;right:18px;top:18px;width:48px;height:48px;border:0;background:#edf3f1}.payment-methods{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:18px 0}.payment-methods button{min-height:58px;padding:0 14px;border:1px solid #cedbd7;background:#fff;display:flex;align-items:center;gap:10px}.payment-methods button>svg:first-child,.qr-method svg{width:27px;height:27px}.payment-methods button>span{flex:1;text-align:left}.payment-methods button>svg:last-child{width:19px;color:#176b63}.payment-methods button.active{border:2px solid #176b63;background:#eaf4f1}.payment-notice{margin:14px 0;padding:12px;background:#fff3cc;color:#694f09}.qr-sheet>section{text-align:center}.qr-sheet canvas{display:block;margin:18px auto}.qr-method{display:flex;align-items:center;justify-content:center;gap:8px}.payment-status{font-weight:800;color:#176b63}.cancel-pay{min-height:48px;border:0;background:transparent;color:#52635e}.membership-active,.membership-message{margin-top:16px;padding:14px;background:#fff}.membership-active{display:flex;gap:10px}.membership-active svg{color:#176b63}.membership-active small{display:block;margin-top:4px}.membership-message.error{color:#9b3026}.membership-message.success{color:#176b63}@media(max-width:680px){.membership-plans{grid-template-columns:1fr}.membership-hero{border-radius:0;margin-inline:-18px}.membership-hero h1{font-size:26px}.payment-methods{grid-template-columns:1fr}}
</style>
