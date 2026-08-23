<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { clearLocalLibrary, favoriteItems, historyItems, listenHistoryItems } from "../library";
import { residentLogin, residentLogout, residentMe, type ResidentProfile } from "../api";
import { UserRound, Heart, Clock3, Headphones, Bell, Settings, SlidersHorizontal, Info, CircleHelp, ShieldCheck, ChevronRight, Trash2, LogOut } from "lucide-vue-next";
const favoriteCount = ref(0); const historyCount = ref(0); const listenCount = ref(0);
const profile = ref<ResidentProfile|null>(null); const username = ref("demo_chen"); const password = ref(""); const loginError = ref(""); const loggingIn = ref(false);
function loadLibrary(){ favoriteCount.value=favoriteItems().length; historyCount.value=historyItems().length; listenCount.value=listenHistoryItems().length; }
function clear(){ if(window.confirm("确认清除本机收藏、浏览和收听历史吗？阅读设置将保留。")){ clearLocalLibrary(); loadLibrary(); } }
async function restoreProfile(){ if(!localStorage.getItem("jianda_resident_token")) return; try{ profile.value=await residentMe(); }catch{ localStorage.removeItem("jianda_resident_token"); localStorage.removeItem("jianda_resident_profile"); } }
async function login(){ loggingIn.value=true;loginError.value="";try{ profile.value=await residentLogin(username.value,password.value); }catch{ loginError.value="账号或密码不正确，请核对课程验收账号。"; }finally{ loggingIn.value=false; } }
async function logout(){ await residentLogout(); profile.value=null; }
onMounted(()=>{loadLibrary();restoreProfile();window.addEventListener("jianda-library-change",loadLibrary)}); onUnmounted(()=>window.removeEventListener("jianda-library-change",loadLibrary));
const links = [
  ["/reminders", Bell, "我的提醒", "报名截止、活动和办理时间", () => ""], ["/favorites", Heart, "我的收藏", "仍在公开的资讯与办事", () => `${favoriteCount.value} 条`],
  ["/history", Clock3, "历史浏览", "最近打开过的内容", () => `${historyCount.value} 条`], ["/listen?tab=recent", Headphones, "最近收听", "继续播放在本机听过的内容", () => `${listenCount.value} 条`],
  ["/neighborhood", UserRound, "我的帖子与评论", "参与大场邻里互动", () => ""], ["/settings", Settings, "阅读与语音设置", "字号、对比度和朗读速度", () => ""],
  ["/settings", SlidersHorizontal, "关注内容", "由您自己选择关心的频道", () => ""],
] as const;
</script>
<template><div class="h5-page"><H5Header/><main class="h5-main profile-page">
  <section v-if="profile" class="resident-card"><span><UserRound/></span><div><small v-if="profile.demo">DEMO 居民账号</small><h1>{{ profile.nickname }}</h1><p>{{ profile.district }} · {{ profile.streetOrTown }}</p></div><button @click="logout"><LogOut/>退出</button></section>
  <section v-else class="resident-login"><div><UserRound/><span><h1>居民登录</h1><p>用于提醒和邻里互动；收藏与阅读设置仍可游客使用。</p></span></div><label>账号<input v-model="username" autocomplete="username"/></label><label>密码<input v-model="password" type="password" autocomplete="current-password"/></label><p class="demo-account-note">课程验收账号不对应真实居民，请使用分配的密码。</p><p v-if="loginError" class="form-error">{{ loginError }}</p><button :disabled="loggingIn" @click="login">{{ loggingIn ? "登录中…" : "登录" }}</button></section>
  <section class="profile-links"><RouterLink v-for="link in links" :key="link[2]" :to="link[0]"><component :is="link[1]"/><span><b>{{ link[2] }}</b><small>{{ link[3] }}</small></span><em>{{ link[4]() }}</em><ChevronRight/></RouterLink></section>
  <section class="profile-links secondary"><div><CircleHelp/><span><b>帮助与反馈</b><small>查看使用说明和反馈渠道</small></span></div><div><ShieldCheck/><span><b>隐私说明</b><small>不使用浏览器指纹、广告 ID 或精确定位</small></span></div><div><Info/><span><b>关于简达</b><small>人工审核的适老化公共服务平台</small></span></div></section>
  <button class="clear-local" type="button" @click="clear"><Trash2/>清除本机收藏和历史</button>
</main><BottomNav/></div></template>
