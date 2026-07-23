<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { askAssistant, fetchAssistantSuggestions, fetchDetail, type AssistantCitation } from "../api";
import { BookOpenCheck, ChevronRight, CircleAlert, Clock3, MessageCircleQuestion, Mic, Send, Trash2 } from "lucide-vue-next";

interface ConversationMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  citations?: AssistantCitation[];
  disclaimer?: string;
  createdAt: string;
}

const SESSION_KEY = "jianda_assistant_session";
const route = useRoute();
const question = ref("");
const suggestions = ref<string[]>([]);
const messages = ref<ConversationMessage[]>(readSession());
const busy = ref(false);
const error = ref("");
const conversation = ref<HTMLElement>();
const contextTitle = ref("");
const contextSlug = computed(() => String(route.query.about || ""));
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
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: "smooth" });
}
async function submit(value = question.value) {
  const text = value.trim();
  if (!text || busy.value) return;
  question.value = "";
  error.value = "";
  messages.value.push({ id: crypto.randomUUID(), role: "user", text, createdAt: new Date().toISOString() });
  saveSession();
  busy.value = true;
  await scrollToLatest();
  try {
    const reply = await askAssistant(text, contextSlug.value);
    messages.value.push({
      id: crypto.randomUUID(),
      role: "assistant",
      text: reply.answer,
      citations: reply.citations,
      disclaimer: reply.disclaimer,
      createdAt: new Date().toISOString(),
    });
    saveSession();
  } catch {
    error.value = "暂时无法连接简达助手，请稍后重试。已输入的问题仍保存在本机。";
  } finally {
    busy.value = false;
    await scrollToLatest();
  }
}
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
  return `/${citation.kind}/${citation.slug}`;
}
function formatDate(value: string) {
  return value ? new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric" }).format(new Date(value)) : "发布时间待核对";
}

onMounted(async () => {
  try { suggestions.value = await fetchAssistantSuggestions(); }
  catch { suggestions.value = []; }
  if (contextSlug.value) {
    try {
      const detail = await fetchDetail(contextSlug.value);
      contextTitle.value = String(detail.title || "");
    } catch { contextTitle.value = ""; }
  }
  await scrollToLatest();
});
</script>

<template>
  <div class="h5-page">
    <H5Header />
    <main class="h5-main assistant-page">
      <header class="assistant-hero">
        <div class="assistant-hero__title">
          <MessageCircleQuestion />
          <div><h1>简达助手</h1><p>只依据平台已人工审核并发布的内容回答，并为每条结论附上来源。</p></div>
        </div>
        <div class="assistant-trust"><BookOpenCheck /><span><b>可核对，不猜测</b><small>找不到可靠依据时会明确告诉您</small></span></div>
      </header>

      <section v-if="contextTitle" class="assistant-context">
        <span>正在询问这项内容</span><b>{{ contextTitle }}</b>
        <RouterLink :to="`/guide/${contextSlug}`">查看详情 <ChevronRight /></RouterLink>
      </section>

      <section v-if="!messages.length" class="assistant-starter">
        <h2>您可以这样问</h2>
        <div class="assistant-suggestions">
          <button v-for="item in suggestions" :key="item" type="button" @click="submit(item)">{{ item }}</button>
        </div>
        <p v-if="!suggestions.length">输入政策、健康、反诈或办事方面的问题，助手会在已发布内容中查找依据。</p>
      </section>

      <section ref="conversation" class="assistant-conversation" aria-live="polite" aria-label="问答记录">
        <article v-for="message in messages" :key="message.id" class="assistant-message" :class="`assistant-message--${message.role}`">
          <small>{{ message.role === "user" ? "您" : "简达助手" }}</small>
          <div class="assistant-bubble">{{ message.text }}</div>
          <div v-if="message.citations?.length" class="assistant-citations">
            <h3>回答依据</h3>
            <RouterLink v-for="citation in message.citations" :key="citation.slug" :to="detailPath(citation)" class="assistant-citation">
              <span>{{ citation.category }} · {{ citation.sourceName }} · {{ formatDate(citation.publishedAt) }}</span><b>{{ citation.title }}</b>
              <blockquote>“{{ citation.quote }}”</blockquote>
              <small>查看完整内容与原文 <ChevronRight /></small>
            </RouterLink>
          </div>
          <p v-if="message.disclaimer" class="assistant-disclaimer"><CircleAlert />{{ message.disclaimer }}</p>
        </article>
        <div v-if="busy" class="assistant-thinking">正在查找已审核内容并核对来源…</div>
      </section>

      <p v-if="error" class="assistant-error" role="alert">{{ error }}</p>

      <section class="assistant-composer">
        <div class="assistant-session-bar">
          <span>问答记录仅保存在本机</span>
          <div>
            <RouterLink to="/assistant/history"><Clock3 />历史会话</RouterLink>
            <button type="button" :disabled="!messages.length" @click="clearSession"><Trash2 />清空</button>
          </div>
        </div>
        <form @submit.prevent="submit()">
          <label for="assistant-question">输入您想了解的问题</label>
          <textarea id="assistant-question" v-model="question" maxlength="500" rows="2" placeholder="例如：办理这项业务需要准备什么材料？" @keydown.ctrl.enter.prevent="submit()" />
          <button class="speech-input" type="button" :disabled="!speechSupported" @click="startSpeechInput"><Mic />{{ speechSupported ? "语音输入" : "当前浏览器不支持语音输入" }}</button>
          <button class="send-question" type="submit" :disabled="busy || !question.trim()"><Send />发送问题</button>
        </form>
      </section>
    </main>
    <BottomNav />
  </div>
</template>
