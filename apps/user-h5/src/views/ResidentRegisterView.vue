<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Phone, UserRound, Lock, UserCircle2, MapPin } from "lucide-vue-next";
import { residentRegister, residentRegistrationCapabilities } from "../api";
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
    await residentRegister(username.value.trim(), password.value, nickname.value.trim(), regionCode.value, phone.value.trim());
    const redirect = typeof route.query.redirect === "string" && route.query.redirect.startsWith("/")
      ? route.query.redirect
      : "/";
    await router.replace(redirect);
  } catch (e: any) {
    error.value = e?.response?.data?.message || "注册失败，请稍后再试";
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
            <svg viewBox="0 0 48 48" width="48" height="48" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect width="48" height="48" rx="14" fill="#0E5A55"/>
              <path d="M15 34V18h8c4.4 0 8 3.1 8 7.6S27.4 33.2 23 33.2h-2.9V34H15Zm4.4-4.1h3c2.2 0 3.8-1.4 3.8-3.5s-1.6-3.5-3.8-3.5h-3V29.9Z" fill="#F7F4EE"/>
            </svg>
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
