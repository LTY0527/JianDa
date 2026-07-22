<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { authApi } from "../api/documents";
import { apiMessage } from "../api/http";
import {
  HeartHandshake,
  ShieldCheck,
  FileSearch,
  Volume2,
} from "lucide-vue-next";
const username = ref("org_admin"),
  password = ref("Jianda@123"),
  loading = ref(false),
  error = ref("");
const router = useRouter();
async function login() {
  if (!username.value || !password.value) {
    error.value = "请输入账号和密码";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const response = await authApi.login(username.value, password.value);
    localStorage.setItem("jianda_token", response.data.data.token);
    localStorage.setItem("jianda_user", username.value);
    localStorage.setItem(
      "jianda_user_info",
      JSON.stringify(response.data.data.user),
    );
    await router.push("/");
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <div class="login-page">
    <section class="login-story">
      <div class="login-brand"><HeartHandshake :size="26" /><b>简达</b></div>
      <div>
        <h1>让公共服务信息<br />更清楚、更好办</h1>
        <p>
          将复杂通知转化为可信、易读、可追溯的办事指南，帮助每一位长者看懂政策、顺利办事。
        </p>
        <ul>
          <li><ShieldCheck />权威来源，人工审核后发布</li>
          <li><FileSearch />关键字段可回看原文依据</li>
          <li><Volume2 />大字阅读与语音播报</li>
        </ul>
      </div>
      <small>简达 · 公共服务信息适老化平台</small>
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="login">
        <h2>登录机构工作台</h2>
        <p>使用分配给您的机构账号登录</p>
        <label
          >账号<input
            v-model="username"
            autocomplete="username"
            placeholder="请输入账号" /></label
        ><label
          >密码<input
            v-model="password"
            autocomplete="current-password"
            type="password"
            placeholder="请输入密码"
        /></label>
        <div class="login-options">
          <label><input type="checkbox" checked /> 记住账号</label
          ><a>忘记密码？</a>
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary wide" :disabled="loading">
          {{ loading ? "正在登录…" : "登录" }}
        </button>
        <div class="demo-note">
          <b>演示账号</b><span>org_admin / Jianda@123</span>
        </div>
      </form>
    </section>
  </div>
</template>
