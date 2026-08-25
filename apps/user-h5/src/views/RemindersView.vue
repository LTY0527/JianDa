<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import BottomNav from "../components/BottomNav.vue";
import { deleteReminder, fetchReminders, type ResidentReminder } from "../api";
import { BellRing, CalendarClock, Trash2, WifiOff } from "lucide-vue-next";

const reminders = ref<ResidentReminder[]>([]);
const loading = ref(true);
const error = ref("");
const groups = computed(() => ({
  upcoming: reminders.value.filter((item) => new Date(item.remind_at).getTime() >= Date.now()),
  ended: reminders.value.filter((item) => new Date(item.remind_at).getTime() < Date.now()),
}));
function kind(item: ResidentReminder) {
  return item.content_kind === "SERVICE_NOTICE" ? "guide" : "news";
}
function label(item: ResidentReminder) {
  if (new Date(item.remind_at).getTime() < Date.now()) return "已结束";
  const hours = (new Date(item.remind_at).getTime() - Date.now()) / 36e5;
  return hours <= 48 ? "即将开始" : item.reminder_type === "DEADLINE" ? "即将截止" : "已设置";
}
function time(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
async function load() {
  loading.value = true; error.value = "";
  try { reminders.value = await fetchReminders(); }
  catch { error.value = "提醒暂时无法读取，请稍后重试。"; }
  finally { loading.value = false; }
}
async function remove(id: number) {
  try { await deleteReminder(id); reminders.value = reminders.value.filter((item) => item.id !== id); }
  catch { error.value = "删除提醒失败，请稍后重试。"; }
}
onMounted(load);
</script>

<template><div class="h5-page"><AppTopBar/><main class="h5-main reminder-page">
  <header class="app-section-head"><BellRing/><div><h1>我的提醒</h1><p>提醒只保存在简达，不会读取通讯录或精确位置。</p></div></header>
  <div v-if="loading" class="compact-empty">正在读取提醒……</div>
  <div v-else-if="error" class="home-error"><WifiOff/><div><b>提醒加载失败</b><p>{{ error }}</p></div><button @click="load">重新加载</button></div>
  <template v-else>
    <section class="reminder-group"><h2>接下来</h2><article v-for="item in groups.upcoming" :key="item.id"><CalendarClock/><RouterLink :to="`/${kind(item)}/${item.slug}`"><span>{{ label(item) }}</span><h3>{{ item.title }}</h3><p>{{ time(item.remind_at) }}</p></RouterLink><button type="button" aria-label="删除提醒" @click="remove(item.id)"><Trash2/></button></article><p v-if="!groups.upcoming.length" class="compact-empty">还没有即将到来的提醒。</p></section>
    <section v-if="groups.ended.length" class="reminder-group reminder-group--ended"><h2>已结束</h2><article v-for="item in groups.ended" :key="item.id"><CalendarClock/><RouterLink :to="`/${kind(item)}/${item.slug}`"><span>已结束</span><h3>{{ item.title }}</h3><p>{{ time(item.remind_at) }}</p></RouterLink><button type="button" aria-label="删除提醒" @click="remove(item.id)"><Trash2/></button></article></section>
  </template>
</main><BottomNav/></div></template>
