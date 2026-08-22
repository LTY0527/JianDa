<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Heart, MapPin, MessageCircle, Send, ShieldAlert, UsersRound, WifiOff } from "lucide-vue-next";
import BottomNav from "../components/BottomNav.vue";
import H5Header from "../components/H5Header.vue";
import { addCommunityComment, createCommunityPost, fetchCommunityPosts, reportCommunityPost, toggleCommunityLike, type CommunityPost } from "../api";
import { activeRegion } from "../region";

const posts = ref<CommunityPost[]>([]);
const tab = ref<"最新" | "互助" | "活动">("最新");
const draft = ref("");
const error = ref("");
const loading = ref(true);
const submitting = ref(false);
const loggedIn = computed(() => Boolean(localStorage.getItem("jianda_resident_token")));
function format(value: string) { return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value)); }
async function load() { loading.value = true; error.value = ""; try { posts.value = await fetchCommunityPosts(tab.value, activeRegion.value.region_code); } catch { error.value = "邻里消息暂时无法读取"; } finally { loading.value = false; } }
async function select(next: typeof tab.value) { tab.value = next; await load(); }
async function publish() { if (!draft.value.trim()) return; submitting.value = true; try { await createCommunityPost(tab.value === "最新" ? "互助" : tab.value, draft.value); draft.value = ""; await load(); } catch { error.value = "发布失败，请确认已经登录且内容不超过 500 字。"; } finally { submitting.value = false; } }
async function like(post: CommunityPost) { if (!loggedIn.value) { error.value = "登录居民账号后可以点赞。"; return; } try { const liked = await toggleCommunityLike(post.id); post.like_count += liked ? 1 : -1; } catch { error.value = "点赞失败，请稍后重试。"; } }
async function comment(post: CommunityPost) { if (!loggedIn.value) { error.value = "登录居民账号后可以评论。"; return; } const content = window.prompt("写一句友善、简短的评论（不超过 300 字）"); if (!content) return; try { await addCommunityComment(post.id, content); post.comment_count += 1; } catch { error.value = "评论没有发布成功。"; } }
async function report(post: CommunityPost) { if (!loggedIn.value) { error.value = "登录居民账号后可以举报。"; return; } const reason = window.prompt("请说明举报原因（至少 5 个字）"); if (!reason) return; try { await reportCommunityPost(post.id, reason); error.value = "举报已提交，平台管理员会进行核对。"; } catch { error.value = "举报没有提交成功。"; } }
onMounted(load);
</script>

<template><div class="h5-page"><H5Header/><main class="h5-main neighborhood-page">
  <header class="neighborhood-hero"><div><h1><UsersRound/>大场邻里</h1><p><MapPin/>上海市 · 宝山区 · {{ activeRegion.street_or_town }}</p></div><small>不显示精确小区和门牌</small></header>
  <nav class="neighborhood-tabs" aria-label="邻里分类"><button v-for="item in ['最新','互助','活动']" :key="item" :class="{ active: tab === item }" @click="select(item as typeof tab)">{{ item }}</button></nav>
  <section v-if="loggedIn" class="neighborhood-compose"><label for="post-content">分享一件对邻里有用的事</label><textarea id="post-content" v-model="draft" maxlength="500" rows="3" placeholder="不发布身份证、银行卡、门牌等个人信息"></textarea><footer><span>{{ draft.length }}/500</span><button :disabled="submitting || !draft.trim()" @click="publish"><Send/>{{ submitting ? "发布中…" : "发布" }}</button></footer></section>
  <RouterLink v-else class="neighborhood-login-tip" to="/profile">登录居民 DEMO 账号后可发布、点赞、评论和举报</RouterLink>
  <p v-if="error" class="warm-tip" role="status">{{ error }}</p>
  <div v-if="loading" class="compact-empty">正在读取邻里消息……</div>
  <div v-else-if="!posts.length" class="compact-empty"><WifiOff/>当前没有邻里帖子。</div>
  <section v-else class="neighborhood-feed"><article v-for="post in posts" :key="post.id"><header><span>{{ post.nickname.slice(0,1) }}</span><div><b>{{ post.nickname }} <em v-if="post.user_is_demo">DEMO</em></b><small>{{ post.street_or_town }} · {{ format(post.created_at) }}</small></div><i>{{ post.category }}</i></header><p>{{ post.content }}</p><footer><button @click="like(post)"><Heart/>{{ post.like_count }}</button><button @click="comment(post)"><MessageCircle/>{{ post.comment_count }}</button><button @click="report(post)"><ShieldAlert/>举报</button></footer></article></section>
</main><BottomNav/></div></template>
