<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { AssistantApiError, askAssistant, fetchAssistantStatus, fetchAssistantSuggestions, fetchDetail, type AssistantCitation, type AssistantCommunityPost, type AssistantFactCard, type AssistantRuntimeStatus } from "../api";
import { activeRegion } from "../region";
import { createUuid } from "../utils/visitorId";
import SpeechRateSelector from "../components/SpeechRateSelector.vue";
import { useSpeechPlayer } from "../composables/useSpeechPlayer";
import { BookOpenCheck, ChevronRight, CircleAlert, Clock3, MessageCircleQuestion, Mic, Pause, Play, Send, Square, Trash2, Volume2 } from "lucide-vue-next";

interface ConversationMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  actions?: string[];
  factCards?: AssistantFactCard[];
  communityPosts?: AssistantCommunityPost[];
  citations?: AssistantCitation[];
  disclaimer?: string;
  mode?: "status" | "retrieval" | "ai" | "web_ai" | "general_ai" | "community_post";
  assistantStatus?: AssistantRuntimeStatus;
  createdAt: string;
}

const SESSION_KEY = "jianda_assistant_session";
const route = useRoute();
const router = useRouter();
const question = ref("");
const suggestions = ref<string[]>([]);
const messages = ref<ConversationMessage[]>(readSession());
const busy = ref(false);
const error = ref("");
const failedQuestion = ref("");
const inputSection = ref<HTMLElement>();
const messagesEnd = ref<HTMLElement>();
const contextTitle = ref("");
const contextCategory = ref("");
const contextRegion = ref("");
const contextKind = ref<"news" | "guide">("guide");
const contextUnavailable = ref(false);
const assistantStatus = ref<AssistantRuntimeStatus>("unreachable");
const spokenMessageId = ref("");
const speech = useSpeechPlayer(() => { spokenMessageId.value = ""; });
const contextSlug = computed(() => String(
  route.query.mode === "context" ? route.query.slug || "" : route.query.about || "",
).trim());
const contextActive = computed(() => Boolean(contextSlug.value && contextTitle.value && !contextUnavailable.value));
const contextQuestions = ["需要准备什么？", "什么时候办理？", "在哪里办理？"];
const speechSupported = "SpeechRecognition" in window || "webkitSpeechRecognition" in window;

function readSession(): ConversationMessage[] {
  try { return JSON.parse(localStorage.getItem(SESSION_KEY) || "[]") as ConversationMessage[]; }
  catch { return []; }
}
function saveSession() {
  localStorage.setItem(SESSION_KEY, JSON.stringify(messages.value.slice(-30)));
}
async function scrollToLatest() {
  await nextTick();
  messagesEnd.value?.scrollIntoView({ behavior: "smooth", block: "end" });
}
function assistantErrorMessage(reason: AssistantApiError["reason"]) {
  const messages: Record<AssistantApiError["reason"], string> = {
    network: "网络连接失败，请检查当前网络后重新发送。",
    server: "助手服务返回异常，已保留您的问题，请稍后重新发送。",
    withdrawn: "您询问的内容可能已经撤回，请返回已发布内容后重新选择。",
    busy: "助手服务繁忙，请稍后重新发送。",
    format: "助手返回的内容格式异常，请稍后重新发送。",
  };
  return messages[reason];
}
async function submit(value = question.value, recordUser = true) {
  const text = value.trim();
  if (!text || busy.value) return;
  if (recordUser) question.value = "";
  error.value = "";
  failedQuestion.value = "";
  if (recordUser) {
    messages.value.push({ id: createUuid(), role: "user", text, createdAt: new Date().toISOString() });
    saveSession();
  }
  busy.value = true;
  await scrollToLatest();
  try {
    const reply = await askAssistant(text, contextActive.value ? contextSlug.value : undefined, activeRegion.value.region_code);
    messages.value.push({
      id: createUuid(),
      role: "assistant",
      text: reply.answer,
      actions: reply.actions,
      factCards: reply.factCards,
      communityPosts: reply.communityPosts,
      citations: reply.citations,
      disclaimer: reply.disclaimer,
      mode: reply.mode,
      assistantStatus: reply.assistantStatus,
      createdAt: new Date().toISOString(),
    });
    if (reply.assistantStatus) assistantStatus.value = reply.assistantStatus;
    saveSession();
  } catch (requestError) {
    failedQuestion.value = text;
    error.value = requestError instanceof AssistantApiError
      ? assistantErrorMessage(requestError.reason)
      : assistantErrorMessage("format");
  } finally {
    busy.value = false;
    await scrollToLatest();
  }
}
function retryFailedQuestion() {
  if (failedQuestion.value) void submit(failedQuestion.value, false);
}
function answerSpeechText(message: ConversationMessage) {
  return [message.text, message.disclaimer].filter(Boolean).join("。");
}
function toggleAnswerSpeech(message: ConversationMessage) {
  if (spokenMessageId.value === message.id) {
    speech.toggle(answerSpeechText(message));
    return;
  }
  spokenMessageId.value = message.id;
  speech.play(answerSpeechText(message));
}
function stopAnswerSpeech() {
  speech.stop();
  spokenMessageId.value = "";
}
watch(speech.status, (status) => {
  if (status === "idle") spokenMessageId.value = "";
});
function clearSession() {
  if (messages.value.length && !window.confirm("确定清空本机保存的本次问答记录吗？")) return;
  messages.value = [];
  localStorage.removeItem(SESSION_KEY);
  error.value = "";
}
function startSpeechInput() {
  if (!speechSupported) return;
  const speechWindow = window as typeof window & {
    SpeechRecognition?: new () => any;
    webkitSpeechRecognition?: new () => any;
  };
  const SpeechRecognition = speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition;
  if (!SpeechRecognition) return;
  const recognition = new SpeechRecognition();
  recognition.lang = "zh-CN";
  recognition.interimResults = false;
  recognition.onresult = (event: any) => { question.value = event.results?.[0]?.[0]?.transcript || ""; };
  recognition.onerror = () => { error.value = "未能识别语音，请检查浏览器的麦克风权限，或改用文字输入。"; };
  recognition.start();
}
function detailPath(citation: AssistantCitation) {
  return citation.kind === "external" ? citation.url || "#" : `/${citation.kind}/${citation.slug}`;
}
function formatDate(value: string) {
  return value ? new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric" }).format(new Date(value)) : "发布时间待核对";
}
function modeLabel(mode?: ConversationMessage["mode"]) {
  const labels: Record<NonNullable<ConversationMessage["mode"]>, string> = {
    status: "平台运行状态",
    retrieval: "原文检索",
    ai: "已审核内容 + AI 整理",
    web_ai: "联网资料 + AI 整理",
    general_ai: "通用 AI 参考",
    community_post: "居民邻里信息",
  };
  return mode ? labels[mode] : "原文检索";
}

function statusLabel(status: AssistantRuntimeStatus) {
  return ({
    ready: "AI 可用",
    degraded: "AI 降级",
    unreachable: "AI 无法连接",
    disabled: "AI 未启用",
  } as const)[status];
}

async function loadContext() {
  contextTitle.value = "";
  contextCategory.value = "";
  contextRegion.value = "";
  contextUnavailable.value = false;
  if (!contextSlug.value) return;
  try {
    const detail = await fetchDetail(contextSlug.value, activeRegion.value.region_code);
    contextTitle.value = String(detail.title || "");
    contextCategory.value = String(detail.category || "已审核内容");
    contextRegion.value = String(detail.street_or_town || "当前地区");
    contextKind.value = String(detail.content_kind || "").toUpperCase() === "WEB_ARTICLE" ? "news" : "guide";
    if (!contextTitle.value) throw new Error("missing context title");
  } catch {
    contextUnavailable.value = true;
  }
  await nextTick();
  if (contextActive.value || contextUnavailable.value) {
    inputSection.value?.scrollIntoView({ behavior: "auto", block: "end" });
  }
}

async function exitContext() {
  contextTitle.value = "";
  contextCategory.value = "";
  contextRegion.value = "";
  contextUnavailable.value = false;
  await router.replace({ path: "/assistant" });
}

onMounted(async () => {
  question.value = String(route.query.q || "").trim().slice(0, 500);
  try { suggestions.value = await fetchAssistantSuggestions(); }
  catch { suggestions.value = []; }
  try { assistantStatus.value = (await fetchAssistantStatus()).status; }
  catch { assistantStatus.value = "unreachable"; }
  await loadContext();
});
watch(() => `${route.query.mode || ""}|${route.query.slug || ""}|${route.query.about || ""}`, loadContext);
</script>

<template>
  <div class="h5-page assistant-shell-new">
    <H5Header />
    <main class="h5-main assistant-shell">
      <div class="assistant-inner-head">
        <div class="a-head__brand">
          <span class="a-logo"><MessageCircleQuestion /></span>
          <div>
            <h1>简达助手</h1>
            <small :class="`st st--${assistantStatus}`"><i></i>{{ statusLabel(assistantStatus) }}</small>
          </div>
        </div>
        <div class="a-head__actions">
          <RouterLink to="/assistant/history"><Clock3 /></RouterLink>
          <button type="button" :disabled="!messages.length" @click="clearSession" aria-label="清空会话"><Trash2 /></button>
        </div>
      </div>

      <div class="assistant-chat-body" aria-live="polite" aria-label="问答记录">
        <section v-if="!messages.length" class="chat-welcome">
          <div class="chat-welcome__hero">
            <div class="chat-welcome__avatar"><MessageCircleQuestion /></div>
            <h2>你好，我是简达</h2>
            <p>我会优先使用政府、社区和权威医疗机构的已发布内容回答。居民邻里信息会明确区分，不作为政策依据。</p>
          </div>
          <div class="chat-welcome__trust">
            <div><BookOpenCheck /><span><b>原文核对</b><small>每条回答附来源与引用</small></span></div>
            <div><CircleAlert /><span><b>不确定就说不确定</b><small>AI 不可用时使用确定性检索</small></span></div>
          </div>
          <h3 class="chat-welcome__kicker">试试下面的问题</h3>
          <div class="chat-suggestions">
            <button v-for="item in suggestions" :key="item" type="button" @click="submit(item)">
              <b>{{ item }}</b>
            </button>
          </div>
          <p v-if="!suggestions.length" class="chat-welcome__tip">输入政策、健康、反诈或办事方面的问题开始问答。</p>
        </section>

        <article v-for="message in messages" :key="message.id" class="chat-msg" :class="`chat-msg--${message.role}`">
          <div v-if="message.role === 'assistant'" class="chat-msg__avatar"><MessageCircleQuestion /></div>
          <div class="chat-msg__body">
            <small v-if="message.role === 'assistant'" class="chat-msg__mode">{{ modeLabel(message.mode) }}</small>
            <div class="chat-msg__bubble">{{ message.text }}</div>

            <section v-if="message.actions?.length" class="chat-actions">
              <h3><span></span>行动建议</h3>
              <ol><li v-for="action in message.actions" :key="action">{{ action }}</li></ol>
            </section>

            <section v-if="message.factCards?.length" class="chat-facts">
              <h3><span></span>已核对关键信息</h3>
              <dl>
                <div v-for="fact in message.factCards" :key="`${fact.type}-${fact.label}-${fact.value}`">
                  <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
                </div>
              </dl>
            </section>

            <section v-if="message.communityPosts?.length" class="chat-community">
              <h3><span></span>邻里相关讨论（非官方）</h3>
              <article v-for="post in message.communityPosts" :key="post.id">
                <small>{{ post.category }} · {{ post.nickname }} · {{ post.street_or_town }}</small>
                <p>{{ post.content }}</p>
                <span>{{ formatDate(post.created_at) }}</span>
              </article>
            </section>

            <div v-if="message.role === 'assistant'" class="chat-tools">
              <button type="button" @click="toggleAnswerSpeech(message)">
                <component :is="spokenMessageId === message.id && speech.status.value === 'playing' ? Pause : spokenMessageId === message.id && speech.status.value === 'paused' ? Play : Volume2" />
                {{ spokenMessageId === message.id && speech.status.value === "playing" ? "暂停" : spokenMessageId === message.id && speech.status.value === "paused" ? "继续" : "朗读" }}
              </button>
              <button v-if="spokenMessageId === message.id && speech.isActive.value" type="button" @click="stopAnswerSpeech"><Square />停止</button>
              <SpeechRateSelector :model-value="speech.rate.value" @select="speech.setRate" />
              <span v-if="spokenMessageId === message.id && speech.progress.value.total" class="chat-tools__progress">
                {{ speech.progress.value.current }}/{{ speech.progress.value.total }}
              </span>
            </div>

            <div v-if="message.citations?.length" class="chat-citations">
              <details>
                <summary>查看 <b>{{ message.citations.length }}</b> 个权威来源 <ChevronRight /></summary>
                <template v-for="citation in message.citations" :key="citation.slug || citation.url">
                  <a
                    v-if="citation.kind === 'external'"
                    :href="detailPath(citation)"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="chat-citation"
                  >
                    <header><span>{{ citation.category }}</span><small>{{ citation.sourceName }} · {{ formatDate(citation.publishedAt) }}</small></header>
                    <b>{{ citation.title }}</b>
                    <blockquote>"{{ citation.quote }}"</blockquote>
                  </a>
                  <RouterLink v-else :to="detailPath(citation)" class="chat-citation">
                    <header><span>{{ citation.category }}</span><small>{{ citation.sourceName }} · {{ formatDate(citation.publishedAt) }}</small></header>
                    <b>{{ citation.title }}</b>
                    <blockquote>"{{ citation.quote }}"</blockquote>
                  </RouterLink>
                </template>
              </details>
            </div>

            <p v-if="message.disclaimer" class="chat-disclaimer"><CircleAlert />{{ message.disclaimer }}</p>
          </div>
          <div v-if="message.role === 'user'" class="chat-msg__avatar chat-msg__avatar--user"><span>您</span></div>
        </article>

        <div v-if="busy" class="chat-msg chat-msg--assistant chat-msg--typing">
          <div class="chat-msg__avatar"><MessageCircleQuestion /></div>
          <div class="chat-msg__body chat-msg__body--typing">
            <small class="chat-msg__mode">正在整理</small>
            <div class="chat-dots"><span></span><span></span><span></span></div>
            <div class="chat-stages">
              <span :class="{ done: busy && !error }"><Clock3 />查找权威来源</span>
              <span :class="{ done: busy && !error && messages.length > 0 }"><BookOpenCheck />整理已核对要点</span>
              <span><CircleAlert />标注不确定内容</span>
            </div>
          </div>
        </div>

        <div v-if="error" class="chat-error" role="alert">
          <span>{{ error }}</span>
          <button v-if="failedQuestion" type="button" :disabled="busy" @click="retryFailedQuestion">重新发送</button>
        </div>
        <p v-if="speech.error.value" class="chat-error" role="status">{{ speech.error.value }}</p>
        <div ref="messagesEnd" class="messages-end" aria-hidden="true"></div>
      </div>

      <section ref="inputSection" class="assistant-composer-new">
        <section v-if="contextActive" class="chat-context" aria-label="帖子提问上下文">
          <BookOpenCheck />
          <div><span>正在基于这篇内容提问</span><b>{{ contextTitle }}</b><small>{{ contextCategory }} · {{ contextRegion }}</small></div>
          <nav><RouterLink :to="`/${contextKind}/${contextSlug}`">查看原内容<ChevronRight /></RouterLink><button type="button" @click="exitContext">退出此事项</button></nav>
        </section>
        <section v-else-if="contextUnavailable" class="chat-context chat-context--error" role="alert">
          <CircleAlert /><div><span>该内容当前不可用于提问</span><small>内容可能已撤回，或不属于当前地区。</small></div><button type="button" @click="exitContext">切换为普通提问</button>
        </section>
        <div v-if="contextActive && !messages.length" class="context-quick-questions" aria-label="帖子快捷问题">
          <button v-for="item in contextQuestions" :key="item" type="button" @click="submit(item)">{{ item }}</button>
        </div>
        <small v-if="!messages.length && !contextActive" class="chat-input__tip">问答记录仅保存在本机浏览器</small>
        <form @submit.prevent="submit()">
          <textarea v-model="question" maxlength="500" rows="1" :placeholder="contextActive ? '继续问这篇内容，例如：需要准备什么材料？' : '输入问题，例如：最近有哪些健康提醒？'" @keydown.ctrl.enter.prevent="submit()" @keydown.meta.enter.prevent="submit()" />
          <button class="chat-input__mic" type="button" :disabled="!speechSupported" @click="startSpeechInput" :aria-label="speechSupported ? '语音输入' : '当前浏览器不支持语音输入'"><Mic /></button>
          <button class="chat-input__send" type="submit" :disabled="busy || !question.trim() || contextUnavailable" aria-label="发送"><Send /></button>
        </form>
      </section>
    </main>
    <BottomNav />
  </div>
</template>

<style scoped>
.assistant-shell-new {
  --at: #0E5A55;
  --at-soft: #E7F1EE;
  --ink: #172326;
  --muted: #667378;
  --bg: #FFFFFF;
  --surface: #fff;
  --warn: #D58B32;
  --err: #B84A42;
  color: var(--ink);
}
.assistant-shell {
  padding: 0 24px 360px;
}
.assistant-inner-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 0 16px;
}
.a-head__brand { display: flex; align-items: center; gap: 12px; }
.a-logo {
  width: 44px; height: 44px;
  display: grid; place-items: center;
  border-radius: 12px;
  background: linear-gradient(135deg, #0E5A55, #1A6F69);
  color: #fff;
  box-shadow: 0 6px 18px rgba(14, 90, 85, .22);
}
.a-logo svg { width: 22px; }
.a-head__brand h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: .5px;
}
.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
  font-weight: 600;
  margin-top: 2px;
}
.st i { width: 7px; height: 7px; border-radius: 50%; background: #97A39F; }
.st--ready i { background: #1E9E59; box-shadow: 0 0 0 3px rgba(30, 158, 89, .14); }
.st--degraded i { background: #D58B32; }
.st--unreachable i, .st--disabled i { background: #B84A42; }
.a-head__actions { display: flex; gap: 4px; }
.a-head__actions > * {
  min-width: 44px; height: 44px;
  border: 0; border-radius: 10px;
  background: var(--surface);
  color: #0E5A55;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid #E7ECE9;
  text-decoration: none;
  font-weight: 700;
}
.a-head__actions svg { width: 19px; }
.a-head__actions button:disabled { opacity: .4; }

.chat-context {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px 12px;
  align-items: start;
  padding: 12px 14px;
  background: #E8F3F1;
  color: #172326;
  margin: 0 0 8px;
  border-radius: 12px;
  border: 1px solid #C9DFDA;
}
.chat-context > svg { color: #0E5A55; width: 20px; margin-top: 2px; }
.chat-context div { min-width: 0; }
.chat-context span { display: block; color: #0E5A55; font-size: 12px; font-weight: 800; }
.chat-context b {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  font-size: 14px;
  line-height: 1.45;
  margin-top: 2px;
}
.chat-context small { display: block; margin-top: 3px; color: #667378; font-size: 11px; }
.chat-context nav {
  grid-column: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.chat-context a,
.chat-context button {
  min-height: 36px;
  font-weight: 700;
  color: #0E5A55;
  background: transparent;
  border: 0;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0;
  font-size: 12px;
}
.chat-context a svg { color: #0E5A55; width: 15px; }
.chat-context--error { background: #FFF6F4; border-color: #EBCBC6; }
.chat-context--error > svg,
.chat-context--error span { color: #B84A42; }
.chat-context--error > button { grid-column: 2; justify-self: start; }
.context-quick-questions { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; margin-bottom: 7px; }
.context-quick-questions button {
  min-height: 36px;
  padding: 0 8px;
  border: 1px solid #C9DFDA;
  border-radius: 8px;
  background: #FFFFFF;
  color: #2F7771;
  font-size: 12px;
  font-weight: 700;
}

.assistant-chat-body { padding: 14px 4px 8px; }
.chat-welcome { padding: 12px 4px 22px; }
.chat-welcome__hero {
  padding: 28px 24px;
  background: #fff;
  border-radius: 16px;
  border: 1px solid #E7ECE9;
  text-align: center;
  margin-bottom: 12px;
  box-shadow: 0 6px 20px rgba(23, 35, 38, .04);
}
.chat-welcome__avatar {
  width: 72px; height: 72px;
  margin: 0 auto 14px;
  border-radius: 50%;
  display: grid; place-items: center;
  background: linear-gradient(135deg, #0E5A55, #1A6F69);
  color: #fff;
}
.chat-welcome__avatar svg { width: 34px; }
.chat-welcome__hero h2 {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 800;
  color: #0E5A55;
}
.chat-welcome__hero p {
  margin: 0;
  color: #667378;
  line-height: 1.7;
  font-size: 15px;
}
.chat-welcome__trust {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 22px;
}
.chat-welcome__trust > div {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #E7ECE9;
  border-radius: 12px;
}
.chat-welcome__trust svg {
  width: 22px;
  color: #0E5A55;
  flex: 0 0 22px;
  margin-top: 2px;
}
.chat-welcome__trust b {
  display: block;
  color: #172326;
  font-size: 14px;
}
.chat-welcome__trust small {
  color: #667378;
  font-size: 12px;
  line-height: 1.5;
}
.chat-welcome__kicker {
  margin: 0 4px 10px;
  font-size: 15px;
  color: #172326;
  font-weight: 700;
}
.chat-suggestions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.chat-suggestions button {
  text-align: left;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #E7ECE9;
  border-radius: 12px;
  color: #172326;
  cursor: pointer;
  min-height: 64px;
  transition: .15s ease;
}
.chat-suggestions button:hover {
  border-color: #0E5A55;
  box-shadow: 0 4px 14px rgba(14, 90, 85, .1);
}
.chat-suggestions b {
  font-size: 14px;
  line-height: 1.55;
  font-weight: 600;
}
.chat-welcome__tip {
  color: #667378;
  text-align: center;
  padding: 12px;
  font-size: 14px;
}

.chat-msg {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 10px;
  margin: 14px 0 18px;
}
.chat-msg--user {
  grid-template-columns: minmax(0, 1fr) 40px;
}
.chat-msg--user > .chat-msg__body { order: -1; }
.chat-msg__avatar {
  width: 40px; height: 40px;
  border-radius: 50%;
  display: grid; place-items: center;
  background: linear-gradient(135deg, #0E5A55, #1A6F69);
  color: #fff;
  align-self: flex-start;
  flex: 0 0 40px;
}
.chat-msg__avatar svg { width: 20px; }
.chat-msg__avatar--user {
  background: #D58B32;
  font-weight: 800;
  font-size: 14px;
}
.chat-msg__body { min-width: 0; }
.chat-msg__mode {
  display: inline-block;
  margin: 0 0 6px;
  padding: 3px 8px;
  border-radius: 6px;
  background: #F7F4EE;
  color: #667378;
  font-style: normal;
  font-weight: 600;
  font-size: 11px;
}
.chat-msg__bubble {
  padding: 14px 17px;
  border-radius: 14px;
  background: #fff;
  color: #172326;
  border: 1px solid #E7ECE9;
  line-height: 1.75;
  font-size: 16px;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.chat-msg--user .chat-msg__bubble {
  background: linear-gradient(135deg, #0E5A55, #1A6F69);
  color: #fff;
  border-color: transparent;
}

.chat-actions, .chat-facts, .chat-community {
  margin-top: 12px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #E7ECE9;
  border-radius: 12px;
}
.chat-actions h3, .chat-facts h3, .chat-community h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-size: 14px;
  color: #0E5A55;
  font-weight: 800;
}
.chat-actions h3 span, .chat-facts h3 span, .chat-community h3 span {
  width: 4px;
  height: 16px;
  background: #D58B32;
  border-radius: 3px;
  display: inline-block;
}
.chat-actions ol {
  margin: 0;
  padding-left: 1.3em;
}
.chat-actions li {
  line-height: 1.7;
  margin-top: 4px;
  color: #172326;
  font-size: 15px;
}
.chat-facts dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin: 0;
}
.chat-facts dl > div {
  padding: 10px 12px;
  border-radius: 9px;
  background: #F7F4EE;
  border: 1px solid #E7F1EE;
}
.chat-facts dt {
  color: #667378;
  font-size: 12px;
}
.chat-facts dd {
  margin: 4px 0 0;
  color: #0E5A55;
  font-weight: 800;
  overflow-wrap: anywhere;
  font-size: 14px;
}
.chat-community article {
  padding: 11px 13px;
  margin-top: 8px;
  background: #FFF6E9;
  border-radius: 10px;
  border: 1px solid #F1E2C7;
}
.chat-community article small {
  color: #8A4C16;
  font-weight: 700;
  font-size: 12px;
}
.chat-community article p {
  margin: 6px 0 4px;
  line-height: 1.65;
  color: #172326;
  font-size: 14px;
}
.chat-community article span {
  color: #667378;
  font-size: 12px;
}

.chat-tools {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
  align-items: center;
}
.chat-tools button {
  min-height: 38px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid #E7F1EE;
  background: #fff;
  color: #0E5A55;
  border-radius: 8px;
  font-weight: 700;
  font-size: 13px;
}
.chat-tools svg { width: 16px; }
.chat-tools__progress {
  margin-left: auto;
  color: #667378;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 9px;
  background: #F7F4EE;
  border-radius: 6px;
}

.chat-citations { margin-top: 12px; }
.chat-citations details {
  border-radius: 12px;
  background: #fff;
  border: 1px solid #E7ECE9;
  overflow: hidden;
}
.chat-citations summary {
  list-style: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  cursor: pointer;
  font-size: 14px;
  color: #667378;
  font-weight: 600;
}
.chat-citations summary::-webkit-details-marker { display: none; }
.chat-citations summary b {
  color: #0E5A55;
  margin: 0 2px;
  font-size: 14px;
}
.chat-citations summary svg {
  width: 15px;
  color: #0E5A55;
  margin-left: auto;
  transition: transform .15s;
}
.chat-citations details[open] summary svg { transform: rotate(90deg); }
.chat-citation {
  display: block;
  padding: 14px 16px;
  border-top: 1px solid #F0F3F1;
  text-decoration: none;
  color: #172326;
}
.chat-citation header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.chat-citation header span {
  padding: 2px 8px;
  border-radius: 6px;
  background: #E7F1EE;
  color: #0E5A55;
  font-size: 11px;
  font-weight: 700;
}
.chat-citation header small {
  color: #667378;
  font-size: 12px;
}
.chat-citation b {
  display: block;
  font-size: 15px;
  line-height: 1.5;
  margin-bottom: 6px;
}
.chat-citation blockquote {
  margin: 0;
  padding: 8px 12px;
  border-left: 3px solid #D58B32;
  background: #F7F4EE;
  color: #667378;
  font-size: 13px;
  line-height: 1.6;
  border-radius: 0 8px 8px 0;
}

.chat-disclaimer {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  margin-top: 10px;
  background: #FCEFED;
  border-radius: 10px;
  color: #822A22;
  font-size: 12px;
  line-height: 1.6;
}
.chat-disclaimer svg {
  width: 17px;
  flex: 0 0 17px;
  margin-top: 1px;
}

.chat-msg--typing .chat-msg__bubble { display: none; }
.chat-msg__body--typing {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #E7ECE9;
  border-radius: 12px;
}
.chat-dots {
  display: flex;
  gap: 6px;
  margin: 10px 0 6px;
}
.chat-dots span {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: #0E5A55;
  opacity: .25;
  animation: bounce 1.2s infinite;
}
.chat-dots span:nth-child(2) { animation-delay: .2s; }
.chat-dots span:nth-child(3) { animation-delay: .4s; }
@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .3; }
  30% { transform: translateY(-5px); opacity: 1; }
}

.chat-stages {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.chat-stages span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  border-radius: 7px;
  background: #F7F4EE;
  color: #667378;
  font-size: 12px;
  font-weight: 600;
}
.chat-stages svg { width: 14px; }
.chat-stages span.done { background: #E7F1EE; color: #0E5A55; }

.chat-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #FCEFED;
  color: #822A22;
  border-radius: 10px;
  margin: 10px 0;
  font-size: 13px;
  border: 1px solid #F4CBC5;
  line-height: 1.55;
}
.chat-error span { flex: 1; }
.chat-error button {
  min-height: 40px;
  padding: 0 14px;
  border-radius: 8px;
  background: #B84A42;
  color: #fff;
  border: 0;
  font-weight: 700;
  font-size: 13px;
}

.messages-end {
  height: 1px;
  scroll-margin-bottom: 350px;
}

.assistant-composer-new {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  right: auto;
  bottom: 76px;
  z-index: 11;
  width: min(100%, 760px);
  padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, rgba(255,255,255,0) 0%, #FFFFFF 28%);
  backdrop-filter: saturate(1.2);
}
.chat-input__tip {
  display: block;
  text-align: center;
  color: #667378;
  font-size: 11px;
  margin: 0 4px 6px;
}
.assistant-composer-new form {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 8px;
  align-items: end;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #CFDAD6;
  border-radius: 14px;
  box-shadow: 0 8px 28px rgba(23, 35, 38, .08);
}
.assistant-composer-new textarea {
  resize: none;
  min-height: 40px;
  max-height: 160px;
  border: 0;
  outline: 0;
  background: transparent;
  color: #172326;
  font-size: 16px;
  line-height: 1.6;
  padding: 8px 4px;
}
.chat-input__mic, .chat-input__send {
  min-width: 44px; height: 44px;
  border: 0;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-weight: 700;
}
.chat-input__mic { background: #F7F4EE; color: #0E5A55; }
.chat-input__mic svg { width: 20px; }
.chat-input__mic:disabled { opacity: .45; }
.chat-input__send { background: #0E5A55; color: #fff; }
.chat-input__send:disabled { background: #9BB3AE; }
.chat-input__send svg { width: 20px; }

@media (max-width: 768px) {
  .assistant-shell { padding: 0 14px 360px; }
  .assistant-inner-head { padding: 14px 0 10px; }
  .chat-suggestions { grid-template-columns: 1fr; }
  .chat-welcome__trust { grid-template-columns: 1fr; }
  .assistant-chat-body { padding-inline: 4px; }
  .chat-context { margin-inline: 0; }
  .assistant-composer-new {
    left: 0; right: 0;
    transform: none;
    width: 100%;
    bottom: 72px;
    padding-inline: 12px;
  }
}
</style>
