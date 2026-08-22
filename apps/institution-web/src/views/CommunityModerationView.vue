<script setup lang="ts">
import { onMounted, ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import { communityAdminApi, type ModerationPost } from "../api/community";
import { apiMessage } from "../api/http";
import { Eye, EyeOff, RefreshCw, ShieldAlert } from "lucide-vue-next";
const posts=ref<ModerationPost[]>([]);const loading=ref(false);const error=ref("");
async function load(){loading.value=true;error.value="";try{posts.value=(await communityAdminApi.posts()).data.data;}catch(cause){error.value=apiMessage(cause);}finally{loading.value=false;}}
async function setStatus(post:ModerationPost,status:"VISIBLE"|"HIDDEN"){if(!window.confirm(status==="HIDDEN"?"确认隐藏这条帖子吗？居民端将不再显示。":"确认恢复这条帖子吗？"))return;try{await communityAdminApi.status(post.id,status);await load();}catch(cause){error.value=apiMessage(cause);}}
onMounted(load);
</script>
<template><div><PageHeader title="邻里内容治理" description="只处理被举报或已隐藏的文字帖子，不展示居民精确位置。" :breadcrumbs="['系统记录','邻里内容治理']"><button class="btn secondary" :disabled="loading" @click="load"><RefreshCw/>刷新</button></PageHeader><p v-if="error" class="inline-error">{{error}}</p><section class="panel"><table class="data-table"><thead><tr><th>发布者</th><th>内容</th><th>举报</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="post in posts" :key="post.id"><td>{{post.nickname}}</td><td>{{post.content}}</td><td>{{post.report_count}} 条</td><td>{{post.status==='REPORTED'?'已举报，待处理':'已隐藏'}}</td><td><button v-if="post.status!=='HIDDEN'" class="btn secondary" @click="setStatus(post,'HIDDEN')"><EyeOff/>隐藏</button><button v-else class="btn secondary" @click="setStatus(post,'VISIBLE')"><Eye/>恢复</button></td></tr></tbody></table><div v-if="!loading&&!posts.length" class="empty-state"><ShieldAlert/>当前没有需要处理的邻里举报。</div></section></div></template>
