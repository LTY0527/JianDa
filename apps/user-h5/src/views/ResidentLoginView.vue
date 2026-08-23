<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { LogIn, UserRound } from "lucide-vue-next";
import H5Header from "../components/H5Header.vue";
import { residentLogin } from "../api";
const route=useRoute(); const router=useRouter(); const username=ref(""); const password=ref(""); const error=ref(""); const busy=ref(false);
async function submit(){busy.value=true;error.value="";try{await residentLogin(username.value,password.value);const redirect=typeof route.query.redirect==="string"&&route.query.redirect.startsWith("/")?route.query.redirect:"/profile";await router.replace(redirect);}catch{error.value="账号或密码不正确，请重新输入。";}finally{busy.value=false;}}
</script>
<template><div class="h5-page"><H5Header/><main class="h5-main auth-page"><section class="resident-auth"><header><LogIn/><div><h1>居民登录</h1><p>登录后可发布邻里消息、点赞、评论和举报。</p></div></header><form @submit.prevent="submit"><label>用户名<input v-model.trim="username" required autocomplete="username"/></label><label>密码<input v-model="password" required type="password" autocomplete="current-password"/></label><p v-if="error" class="form-error" role="alert">{{ error }}</p><button :disabled="busy">{{ busy?'登录中…':'登录' }}</button></form><footer><UserRound/>还没有居民账号？<RouterLink :to="{path:'/resident/register',query:{redirect:route.query.redirect}}">使用用户名和密码注册</RouterLink></footer></section></main></div></template>
