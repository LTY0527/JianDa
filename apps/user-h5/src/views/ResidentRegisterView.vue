<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import axios from "axios";
import { HeartHandshake, Phone, UserRound, Lock, UserCircle2, MapPin } from "lucide-vue-next";
import { residentRegister, residentRegistrationCapabilities } from "../api";
import { completeLogin } from "../composables/useResidentAuth";
import { dachangRegion } from "../region";

const route = useRoute();
const router = useRouter();

const phone = ref("");
const username = ref("");
const nickname = ref("");
const password = ref("");
const confirmPassword = ref("");
const regionCode = ref(dachangRegion.region_code || "310101019");
const error = ref("");
const busy = ref(false);

const caps = ref<{ sms: { enabled: boolean; message: string } } | null>(null);
const showSms = computed(() => !!caps.value?.sms?.enabled);

onMounted(async () => {
  try {
    caps.value = await residentRegistrationCapabilities();
  } catch {
    caps.value = { sms: { enabled: false, message: "" } };
  }
});

async function submit() {
  error.value = "";
  if (!phone.value.trim()) {
    error.value = "请输入手机号";
    return;
  }
  if (!/^1[3-9]\d{9}$/.test(phone.value.trim())) {
    error.value = "请输入正确的 11 位手机号";
    return;
  }
  if (nickname.value.trim().length < 2) {
    error.value = "昵称至少需要 2 个字";
    return;
  }
  if (password.value.length < 8 || password.value.length > 72 || !/[A-Za-z]/.test(password.value) || !/\d/.test(password.value)) {
    error.value = "密码需为 8-72 位，且同时包含字母和数字";
    return;
  }
  if (password.value !== confirmPassword.value) {
    error.value = "两次输入的密码不一致";
    return;
  }
  busy.value = true;
  try {
    const profile = await residentRegister(
      username.value.trim(),
      password.value,
      nickname.value.trim(),
      regionCode.value,
      phone.value.trim(),
    );
    completeLogin(profile);
    const redirect = typeof route.query.redirect === "string" && route.query.redirect.startsWith("/")
      ? route.query.redirect
      : "/";
    try {
      await router.replace(redirect);
    } catch (navErr: any) {
      // eslint-disable-next-line no-console
      console.error("[ResidentRegister] router.replace failed:", navErr);
      if (String(window.location.pathname) !== redirect) {
        window.location.replace(redirect);
      }
    }
  } catch (e: any) {
    if (axios.isAxiosError(e) && (!e.response || e.code === "ECONNABORTED")) {
      error.value = "网络连接失败，请稍后重试。";
    } else if (axios.isAxiosError(e) && (e.response?.status === 400 || e.response?.status === 409)) {
      error.value = e.response?.data?.message || "注册失败，用户名或手机号可能已被占用。";
    } else {
      error.value = e?.response?.data?.message || "注册失败，请稍后再试";
    }
    // eslint-disable-next-line no-console
    console.warn("[ResidentRegister] submit error:", e?.message || e);
  } finally {
    busy.value = false;
  }
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
          <h1 class="login-hero__name">注册简达</h1>
        </div>
        <p class="login-hero__tagline">注册后即可使用社区服务和智能助手</p>
      </section>

      <section class="login-form-wrap">
        <form class="login-form" @submit.prevent="submit" novalidate>
          <label class="login-field">
            <span class="login-field__label">
              <Phone :size="16" />
              <span>手机号（必填）</span>
            </span>
            <input
              v-model.trim="phone"
              inputmode="numeric"
              pattern="1[3-9][0-9]{9}"
              maxlength="11"
              autocomplete="tel"
              placeholder="请输入 11 位手机号"
            />
          </label>

          <label class="login-field">
            <span class="login-field__label">
              <UserCircle2 :size="16" />
              <span>昵称（必填）</span>
            </span>
            <input v-model.trim="nickname" minlength="2" maxlength="60" placeholder="怎么称呼您？" autocomplete="nickname"/>
          </label>

          <label class="login-field">
            <span class="login-field__label">
              <Lock :size="16" />
              <span>密码（必填）</span>
            </span>
            <input
              v-model="password"
              type="password"
              minlength="8"
              maxlength="72"
              autocomplete="new-password"
              placeholder="8-72 位，字母与数字组合"
            />
          </label>

          <label class="login-field">
            <span class="login-field__label">
              <Lock :size="16" />
              <span>确认密码（必填）</span>
            </span>
            <input
              v-model="confirmPassword"
              type="password"
              minlength="8"
              maxlength="72"
              autocomplete="new-password"
              placeholder="请再次输入密码"
            />
          </label>

          <label class="login-field">
            <span class="login-field__label">
              <MapPin :size="16" />
              <span>所在街道（默认可修改）</span>
            </span>
            <input v-model="regionCode" maxlength="20" placeholder="街道代码，例如 310101019"/>
          </label>

          <label class="login-field">
            <span class="login-field__label">
              <UserRound :size="16" />
              <span>用户名（可选）</span>
            </span>
            <input
              v-model.trim="username"
              minlength="4"
              maxlength="30"
              pattern="[A-Za-z0-9_]+"
              autocomplete="username"
              placeholder="4-30 位字母、数字或下划线"
            />
          </label>

          <p v-if="error" class="login-error" role="alert">{{ error }}</p>

          <button class="login-submit" type="submit" :disabled="busy">
            {{ busy ? "注册中…" : "完成注册并登录" }}
          </button>
        </form>

        <footer class="login-footer">
          <p class="login-footer__register">
            已有账号？
            <RouterLink :to="{ path: '/resident/login', query: route.query.redirect ? { redirect: route.query.redirect } : {} }">
              立即登录
            </RouterLink>
          </p>
          <p class="login-footer__legal">
            注册即表示同意《用户服务协议》和《隐私政策》
          </p>
        </footer>
      </section>
    </main>
  </div>
</template>
