<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ImagePlus, Heart, MapPin, MessageCircle, Send, ShieldAlert, Trash2, UsersRound, WifiOff } from "lucide-vue-next";
import BottomNav from "../components/BottomNav.vue";
import H5Header from "../components/H5Header.vue";
import { useRoute, useRouter } from "vue-router";
import { addCommunityComment, createCommunityPost, fetchCommunityPosts, reportCommunityPost, toggleCommunityLike, uploadCommunityMedia, type CommunityMedia, type CommunityPost } from "../api";
import { activeRegion } from "../region";

const posts = ref<CommunityPost[]>([]);
const tab = ref<"最新" | "互助" | "活动">("最新");
const draft = ref("");
const error = ref("");
const loading = ref(true);
const submitting = ref(false);
const route = useRoute(); const router = useRouter();
const selected = ref<{file:File;preview:string;uploaded?:CommunityMedia;error?:string}[]>([]);
const uploading = ref(false);
function loginRedirect(){router.push({path:"/resident/login",query:{redirect:route.fullPath}});}
function requireLogin(){if(loggedIn.value)return true;loginRedirect();return false;}
function choose(event:Event){const input=event.target as HTMLInputElement;const files=[...(input.files??[])];if(selected.value.length+files.length>6){error.value="每篇帖子最多选择 6 张图片。";input.value="";return;}for(const file of files){if(!["image/jpeg","image/png"].includes(file.type)||file.size>8*1024*1024){error.value="仅支持不超过 8MB 的 JPG 或 PNG 图片。";continue;}selected.value.push({file,preview:URL.createObjectURL(file)});}input.value="";}
function removeImage(index:number){URL.revokeObjectURL(selected.value[index].preview);selected.value.splice(index,1);}
async function uploadSelected(){uploading.value=true;for(const item of selected.value){if(!item.uploaded)item.uploaded=await uploadCommunityMedia(item.file);}uploading.value=false;return selected.value.map(item=>item.uploaded!.id);}
const loggedIn = computed(() => Boolean(localStorage.getItem("jianda_resident_token")));
function format(value: string) { return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value)); }
async function load() { loading.value = true; error.value = ""; try { posts.value = await fetchCommunityPosts(tab.value, activeRegion.value.region_code); } catch { error.value = "邻里消息暂时无法读取"; } finally { loading.value = false; } }
async function select(next: typeof tab.value) { tab.value = next; await load(); }
async function publish() { if (!draft.value.trim()) return; submitting.value = true; error.value=""; try { const mediaIds=await uploadSelected(); await createCommunityPost(tab.value === "最新" ? "互助" : tab.value, draft.value, mediaIds); draft.value = ""; selected.value.forEach(item=>URL.revokeObjectURL(item.preview));selected.value=[]; await load(); } catch { error.value = "发布失败，请检查图片格式、大小和帖子内容。"; } finally { submitting.value = false; uploading.value=false; } }
async function like(post: CommunityPost) { if (!requireLogin()) return; try { const liked = await toggleCommunityLike(post.id); post.like_count += liked ? 1 : -1; } catch { error.value = "点赞失败，请稍后重试。"; } }
async function comment(post: CommunityPost) { if (!requireLogin()) return; const content = window.prompt("写一句友善、简短的评论（不超过 300 字）"); if (!content) return; try { await addCommunityComment(post.id, content); post.comment_count += 1; } catch { error.value = "评论没有发布成功。"; } }
async function report(post: CommunityPost) { if (!requireLogin()) return; const reason = window.prompt("请说明举报原因（至少 5 个字）"); if (!reason) return; try { await reportCommunityPost(post.id, reason); error.value = "举报已提交，平台管理员会进行核对。"; } catch { error.value = "举报没有提交成功。"; } }
onMounted(load);
watch(() => activeRegion.value.region_code, load);
</script>

<template><div class="h5-page"><H5Header/><main class="h5-main neighborhood-page">
  <header class="neighborhood-hero"><div><h1><UsersRound/>{{ activeRegion.street_or_town.replace('镇','') }}邻里</h1><p><MapPin/>上海市 · 宝山区 · {{ activeRegion.street_or_town }}</p></div><small>不显示精确小区和门牌</small></header>
  <nav class="neighborhood-tabs" aria-label="邻里分类"><button v-for="item in ['最新','互助','活动']" :key="item" :class="{ active: tab === item }" @click="select(item as typeof tab)">{{ item }}</button></nav>
  <section v-if="loggedIn" class="neighborhood-compose"><label for="post-content">分享一件对邻里有用的事</label><textarea id="post-content" v-model="draft" maxlength="500" rows="3" placeholder="不发布身份证、银行卡、门牌等个人信息"></textarea><div v-if="selected.length" class="post-media-preview"><figure v-for="(item,index) in selected" :key="item.preview"><img :src="item.preview" :alt="`待发布图片 ${index+1}`"/><button type="button" :aria-label="`移除图片 ${index+1}`" @click="removeImage(index)"><Trash2/></button></figure></div><footer><span>{{ draft.length }}/500 · {{ selected.length }}/6 张</span><label class="image-picker"><ImagePlus/>选择图片<input type="file" accept="image/jpeg,image/png" multiple @change="choose"/></label><button type="button" :disabled="submitting || !draft.trim()" @click="publish"><Send/>{{ uploading?'上传图片…':submitting ? "发布中…" : "发布" }}</button></footer></section>
  <button v-else class="neighborhood-login-tip" type="button" @click="loginRedirect">登录居民账号后可发布、点赞、评论和举报</button>
  <output v-if="error" class="warm-tip">{{ error }}</output>
  <div v-if="loading" class="compact-empty">正在读取邻里消息……</div>
  <div v-else-if="!posts.length" class="compact-empty"><WifiOff/>当前没有邻里帖子。</div>
  <section v-else class="neighborhood-feed"><article v-for="post in posts" :key="post.id"><header><span>{{ post.nickname.slice(0,1) }}</span><div><b>{{ post.nickname }} <em v-if="post.user_is_demo">演示社区内容</em></b><small>{{ post.street_or_town }} · {{ format(post.created_at) }}</small></div><i>{{ post.category }}</i></header><p>{{ post.content }}</p><div v-if="post.media?.length" class="post-media-grid" :class="`post-media-grid--${Math.min(post.media.length,3)}`"><img v-for="(media,index) in post.media" :key="media.id" :src="media.thumbnailUrl" :alt="`${post.nickname}发布的图片 ${index+1}`" loading="lazy"/></div><footer><button type="button" @click="like(post)"><Heart/>{{ post.like_count }}</button><button type="button" @click="comment(post)"><MessageCircle/>{{ post.comment_count }}</button><button type="button" @click="report(post)"><ShieldAlert/>举报</button></footer></article></section>
</main><BottomNav/></div></template>
