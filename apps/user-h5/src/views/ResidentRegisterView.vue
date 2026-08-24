<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { MessageSquareOff, UserPlus } from "lucide-vue-next";
import H5Header from "../components/H5Header.vue";
import { residentRegister, residentRegistrationCapabilities } from "../api";
import { activeRegion } from "../region";
const route=useRoute();const router=useRouter();const username=ref("");const nickname=ref("");const password=ref("");const confirm=ref("");const smsMessage=ref("短信注册尚未启用，请使用用户名和密码注册");const error=ref("");const busy=ref(false);
onMounted(async()=>{try{const result=await residentRegistrationCapabilities();smsMessage.value=result.sms.message;}catch{/* 状态接口不可用时保留明确的未启用文案 */}});
async function submit(){error.value="";if(password.value!==confirm.value){error.value="两次输入的密码不一致。";return;}busy.value=true;try{await residentRegister(username.value,password.value,nickname.value,activeRegion.value.region_code);const redirect=typeof route.query.redirect==="string"&&route.query.redirect.startsWith("/")?route.query.redirect:"/profile";await router.replace(redirect);}catch(e:any){error.value=e?.response?.data?.message||"注册失败，请检查用户名、密码和昵称。";}finally{busy.value=false;}}
</script>
<template><div class="h5-page"><H5Header/><main class="h5-main auth-page"><section class="resident-auth"><header><UserPlus/><div><h1>注册居民账号</h1><p>第一版使用用户名和密码，不需要手机号。</p></div></header><p class="sms-disabled"><MessageSquareOff/>{{ smsMessage }}</p><form @submit.prevent="submit"><label>用户名<input v-model.trim="username" required minlength="4" maxlength="30" pattern="[A-Za-z0-9_]+" autocomplete="username"/><small>4-30 位字母、数字或下划线</small></label><label>昵称<input v-model.trim="nickname" required minlength="2" maxlength="60" autocomplete="name"/></label><label>密码<input v-model="password" required minlength="8" maxlength="72" type="password" autocomplete="new-password"/><small>至少 8 位，同时包含字母和数字</small></label><label>确认密码<input v-model="confirm" required type="password" autocomplete="new-password"/></label><p v-if="error" class="form-error" role="alert">{{ error }}</p><button :disabled="busy">{{ busy?'注册中…':'注册并登录' }}</button></form><footer>已有账号？<RouterLink :to="{path:'/resident/login',query:{redirect:route.query.redirect}}">返回登录</RouterLink></footer></section></main></div></template>
