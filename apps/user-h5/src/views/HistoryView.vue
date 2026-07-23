<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import BottomNav from "../components/BottomNav.vue";
import { clearHistory, historyItems, removeHistory, type LibraryItem } from "../library";
import { Clock3, Trash2 } from "lucide-vue-next";
const items = ref<LibraryItem[]>([]);
function load() { items.value = historyItems(); }
function remove(id:number) { removeHistory(id); load(); }
function clear() { if (window.confirm("确认清空本机浏览历史吗？")) { clearHistory(); load(); } }
onMounted(() => { load(); window.addEventListener("jianda-library-change", load); });
onUnmounted(() => window.removeEventListener("jianda-library-change", load));
</script>
<template><div class="h5-page"><AppTopBar title="历史浏览" /><main class="h5-main library-page"><header class="library-head"><div><h1>历史浏览</h1><p>以下记录仅保存在当前设备。</p></div><button v-if="items.length" type="button" @click="clear"><Trash2 />清空</button></header><section v-if="items.length" class="history-list"><article v-for="item in items" :key="item.id"><RouterLink :to="`/${item.kind}/${item.slug}`"><span>{{ item.category }}</span><h2>{{ item.title }}</h2><p>{{ item.source_name }} · 最近浏览 {{ new Date(item.visitedAt || '').toLocaleString('zh-CN') }}</p></RouterLink><button type="button" aria-label="删除历史" @click="remove(item.id)"><Trash2 /></button></article></section><div v-else class="empty"><Clock3 /><b>还没有浏览记录</b><p>打开资讯或办事详情后，会自动记录在这里。</p><RouterLink class="green-link" to="/news">去看看资讯</RouterLink></div></main><BottomNav /></div></template>