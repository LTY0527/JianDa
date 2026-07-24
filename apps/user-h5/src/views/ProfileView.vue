<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { clearLocalLibrary, favoriteItems, historyItems, listenHistoryItems } from "../library";
import { UserRound, Heart, Clock3, Headphones, Bell, Settings, SlidersHorizontal, Info, CircleHelp, ShieldCheck, ChevronRight, Trash2 } from "lucide-vue-next";
const favoriteCount = ref(0); const historyCount = ref(0); const listenCount = ref(0);
function load(){ favoriteCount.value=favoriteItems().length; historyCount.value=historyItems().length; listenCount.value=listenHistoryItems().length; }
function clear(){ if(window.confirm("确认清除本机收藏和浏览历史吗？阅读设置将保留。")){ clearLocalLibrary(); load(); } }
onMounted(()=>{load();window.addEventListener("jianda-library-change",load)}); onUnmounted(()=>window.removeEventListener("jianda-library-change",load));
const links = [
  ["/favorites", Heart, "我的收藏", "仍在公开的资讯与办事", () => `${favoriteCount.value} 条`],
  ["/history", Clock3, "历史浏览", "最近打开过的内容", () => `${historyCount.value} 条`],
  ["/listen?tab=recent", Headphones, "最近收听", "继续播放在本机听过的内容", () => `${listenCount.value} 条`],
  ["/settings", Settings, "阅读与语音设置", "字号、对比度和朗读速度", () => ""],
  ["/settings", SlidersHorizontal, "内容偏好", "关注频道与首页最近浏览", () => ""],
] as const;
</script>
<template><div class="h5-page"><H5Header /><main class="h5-main profile-page"><section class="guest-card"><span><UserRound /></span><div><h1>游客使用</h1><p>收藏、历史和偏好仅保存在当前设备，登录与注册后续开放。</p></div></section><section class="profile-links"><RouterLink v-for="link in links" :key="link[2]" :to="link[0]"><component :is="link[1]" /><span><b>{{ link[2] }}</b><small>{{ link[3] }}</small></span><em>{{ link[4]() }}</em><ChevronRight /></RouterLink></section><section class="profile-links secondary"><div><Bell /><span><b>办事提醒</b><small>提醒能力将在后续版本开放</small></span><em>未开启</em></div><div><CircleHelp /><span><b>帮助与反馈</b><small>查看使用说明和反馈渠道</small></span></div><div><ShieldCheck /><span><b>隐私说明</b><small>本机数据不会用于公开展示</small></span></div><div><Info /><span><b>关于简达</b><small>人工审核的适老化公共服务平台</small></span></div></section><button class="clear-local" type="button" @click="clear"><Trash2 />清除本机收藏和历史</button></main><BottomNav /></div></template>
