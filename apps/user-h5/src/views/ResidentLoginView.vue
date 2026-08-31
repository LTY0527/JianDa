<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import axios from "axios";
import { HeartHandshake, Phone, UserRound, ShieldCheck } from "lucide-vue-next";
import { residentLogin, residentRegistrationCapabilities } from "../api";
import { completeLogin } from "../composables/useResidentAuth";
const route = useRoute();
const router = useRouter();

const mode = ref<"phone" | "username">("phone");
const phone = ref("");
const username = ref("");
const password = ref("");
const error = ref("");
const busy = ref(false);
const smsEnabled = ref(false);

onMounted(async () => {
  try {
    const caps = await residentRegistrationCapabilities();
    smsEnabled.value = !!caps.sms?.enabled;
  } catch {
    smsEnabled.value = false;
  }
});

async function submit() {
  busy.value = true;
  error.value = "";
  try {
    const loginId = mode.value === "phone" ? phone.value.trim() : username.value.trim();
    const profile = await residentLogin(loginId, password.value);
    completeLogin(profile);
    const redirect = typeof route.query.redirect === "string" && route.query.redirect.startsWith("/")
      ? route.query.redirect
      : "/";
    try {
      await router.replace(redirect);
    } catch (navErr: any) {
      // eslint-disable-next-line no-console
      console.error("[ResidentLogin] router.replace failed:", navErr);
      if (String(window.location.pathname) !== redirect) {
        window.location.replace(redirect);
      }
    }
  } catch (e: any) {
    if (axios.isAxiosError(e) && (!e.response || e.code === "ECONNABORTED")) {
      error.value = "网络连接失败，请稍后重试。";
    } else if (axios.isAxiosError(e) && (e.response?.status === 401 || e.response?.status === 403)) {
      error.value = e.response?.data?.message || "账号或密码不正确。";
    } else {
      error.value = e?.response?.data?.message || "账号或密码不正确，请重新输入。";
    }
    // eslint-disable-next-line no-console
    console.warn("[ResidentLogin] submit error:", e?.message || e);
  } finally {
    busy.value = false;
  }
}

function switchMode(next: "phone" | "username") {
  mode.value = next;
  error.value = "";
}
</script>

<template>
  <div class="h5-page login-page">
    <main class="h5-main login-page__main">
      <section class="login-hero">
        <div class="login-hero__brand">
          <div class="login-logo" aria-hidden="true">
            <HeartHandshake />
          </div>
          <h1 class="login-hero__name">简达</h1>
        </div>
        <p class="login-hero__tagline">社区里的事，讲得更明白。</p>
        <p class="login-hero__sub">权威通知、办事提醒和邻里服务，都在这里。</p>
      </section>

      <section class="login-form-wrap">
        <div class="login-tabs">
          <button
            :class="['login-tab', { 'is-active': mode === 'phone' }]"
            type="button"
            @click="switchMode('phone')"
          >
            <Phone :size="18" />
            <span>手机号登录</span>
          </button>
          <button
            :class="['login-tab', { 'is-active': mode === 'username' }]"
            type="button"
            @click="switchMode('username')"
          >
            <UserRound :size="18" />
            <span>用户名登录</span>
          </button>
        </div>

        <form class="login-form" @submit.prevent="submit" novalidate>
          <label v-if="mode === 'phone'" class="login-field">
            <span class="login-field__label">手机号</span>
            <input
              v-model.trim="phone"
              required
              inputmode="numeric"
              pattern="1[3-9][0-9]{9}"
              maxlength="11"
              autocomplete="tel"
              placeholder="请输入 11 位手机号"
            />
          </label>

          <label v-else class="login-field">
            <span class="login-field__label">用户名</span>
            <input
              v-model.trim="username"
              required
              minlength="4"
              maxlength="30"
              pattern="[A-Za-z0-9_]+"
              autocomplete="username"
              placeholder="4-30 位字母、数字或下划线"
            />
          </label>

          <label class="login-field">
            <span class="login-field__label">密码</span>
            <input
              v-model="password"
              required
              type="password"
              minlength="8"
              maxlength="72"
              autocomplete="current-password"
              placeholder="请输入密码"
            />
          </label>

          <p v-if="error" class="login-error" role="alert">{{ error }}</p>

          <button class="login-submit" type="submit" :disabled="busy">
            {{ busy ? "登录中…" : "登录" }}
          </button>
        </form>

        <footer class="login-footer">
          <p class="login-footer__register">
            还没有账号？
            <RouterLink :to="{ path: '/resident/register', query: route.query.redirect ? { redirect: route.query.redirect } : {} }">
              注册账号
            </RouterLink>
          </p>
          <p class="login-footer__legal">
            <ShieldCheck :size="14" />
            <span>登录即表示同意《用户服务协议》和《隐私政策》</span>
          </p>
        </footer>
      </section>
    </main>
  </div>
</template>
