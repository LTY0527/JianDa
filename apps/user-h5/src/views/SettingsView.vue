<script setup lang="ts">
import { reactive, ref } from "vue";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import BottomNav from "../components/BottomNav.vue";
import { readerPreferences, saveReaderPreferences } from "../library";
import { Type, Volume2, Contrast, Play, LayoutList } from "lucide-vue-next";
const size = ref(Number(localStorage.getItem("jianda_font") || 18));
const rate = ref(Number(localStorage.getItem("jianda_rate") || 0.9));
const contrast = ref(localStorage.getItem("jianda_contrast") === "1");
const preferences = reactive(readerPreferences());
const channels = ["政策","健康","养老","反诈","生活","文化"];
function save() { localStorage.setItem("jianda_font", String(size.value)); localStorage.setItem("jianda_rate", String(rate.value)); localStorage.setItem("jianda_contrast", contrast.value ? "1" : "0"); saveReaderPreferences({ ...preferences }); window.dispatchEvent(new CustomEvent("jianda-appearance-change")); }
function toggleChannel(channel:string){ preferences.channels = preferences.channels.includes(channel) ? preferences.channels.filter((item)=>item!==channel) : [...preferences.channels, channel]; save(); }
</script>
<template><div class="h5-page" :class="{ contrast }"><AppTopBar title="阅读设置" /><main class="h5-main settings"><div class="simple-head"><h1>阅读与内容偏好</h1><p>修改后立即保存在本机并影响首页和详情。</p></div>
<section><header><Type /><div><h2>正文字号</h2><p>当前 {{ size }} 像素</p></div></header><div class="font-options"><button v-for="n in [18,20,22,24]" :key="n" :class="{active:size===n}" @click="size=n;save()" :style="{fontSize:n+'px'}">{{ n }}</button></div><p class="preview" :style="{fontSize:size+'px'}">这是一段阅读效果示例。办事前，请准备好所需材料。</p></section>
<section><header><Volume2 /><div><h2>语音速度</h2><p>选择适合您的朗读速度</p></div></header><div class="rate-options"><button v-for="r in [0.7,0.9,1.1]" :key="r" :class="{active:rate===r}" @click="rate=r;save()">{{ r===0.7?'较慢':r===0.9?'适中':'较快' }}</button></div></section>
<section class="setting-switches"><header><Contrast /><div><h2>高对比度</h2><p>加深文字和边框</p></div><label class="switch"><input type="checkbox" v-model="contrast" @change="save" /><i></i></label></header><header><Play /><div><h2>打开详情后自动朗读</h2><p>仅在浏览器支持语音时生效</p></div><label class="switch"><input type="checkbox" v-model="preferences.autoRead" @change="save" /><i></i></label></header><header><LayoutList /><div><h2>首页显示最近浏览</h2><p>关闭后历史仍保存在“我的”</p></div><label class="switch"><input type="checkbox" v-model="preferences.showRecent" @change="save" /><i></i></label></header></section>
<section><header><LayoutList /><div><h2>感兴趣的频道</h2><p>用于调整“今日必看”的规则排序</p></div></header><div class="preference-channels"><button v-for="item in channels" :key="item" :class="{active:preferences.channels.includes(item)}" @click="toggleChannel(item)">{{ item }}</button></div></section>
</main><BottomNav /></div></template>