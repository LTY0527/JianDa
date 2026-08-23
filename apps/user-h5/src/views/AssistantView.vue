<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { AssistantApiError, askAssistant, fetchAssistantSuggestions, fetchDetail, type AssistantCitation, type AssistantFactCard } from "../api";
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
  citations?: AssistantCitation[];
  disclaimer?: string;
  mode?: "status" | "retrieval" | "ai" | "general_ai";
  createdAt: string;
}

const SESSION_KEY = "jianda_assistant_session";
const route = useRoute();
const question = ref("");
const suggestions = ref<string[]>([]);
const messages = ref<ConversationMessage[]>(readSession());
const busy = ref(false);
const error = ref("");
const failedQuestion = ref("");
const conversation = ref<HTMLElement>();
const contextTitle = ref("");
const spokenMessageId = ref("");
const speech = useSpeechPlayer(() => { spokenMessageId.value = ""; });
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
    const reply = await askAssistant(text, contextSlug.value);
    messages.value.push({
      id: createUuid(),
      role: "assistant",
      text: reply.answer,
      actions: reply.actions,
      factCards: reply.factCards,
      citations: reply.citations,
      disclaimer: reply.disclaimer,
      mode: reply.mode,
      createdAt: new Date().toISOString(),
    });
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
  return `/${citation.kind}/${citation.slug}`;
}
function formatDate(value: string) {
  return value ? new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric" }).format(new Date(value)) : "发布时间待核对";
}
function modeLabel(mode?: ConversationMessage["mode"]) {
  const labels: Record<NonNullable<ConversationMessage["mode"]>, string> = {
    status: "平台运行状态",
    retrieval: "原文检索",
    ai: "已审核内容 + AI 整理",
    general_ai: "通用 AI 参考",
  };
  return mode ? labels[mode] : "原文检索";
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
          <p v-if="message.role === 'assistant'" class="assistant-mode">
            {{ modeLabel(message.mode) }}
          </p>
          <section v-if="message.actions?.length" class="assistant-actions">
            <h3>你现在可以怎么做</h3>
            <ol><li v-for="action in message.actions" :key="action">{{ action }}</li></ol>
          </section>
          <section v-if="message.factCards?.length" class="assistant-facts" aria-label="已核对关键信息">
            <h3>已核对关键信息</h3>
            <dl>
              <div v-for="fact in message.factCards" :key="`${fact.type}-${fact.label}-${fact.value}`">
                <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
              </div>
            </dl>
          </section>
          <div v-if="message.role === 'assistant'" class="assistant-speech">
            <button type="button" @click="toggleAnswerSpeech(message)">
              <component :is="spokenMessageId === message.id && speech.status.value === 'playing' ? Pause : spokenMessageId === message.id && speech.status.value === 'paused' ? Play : Volume2" />
              {{ spokenMessageId === message.id && speech.status.value === "playing" ? "暂停播报" : spokenMessageId === message.id && speech.status.value === "paused" ? "继续播报" : "朗读回答" }}
            </button>
            <button v-if="spokenMessageId === message.id && speech.isActive.value" type="button" @click="stopAnswerSpeech"><Square />停止</button>
            <SpeechRateSelector :model-value="speech.rate.value" @select="speech.setRate" />
            <span v-if="spokenMessageId === message.id && speech.progress.value.total" class="speech-progress">
              第 {{ speech.progress.value.current }} / {{ speech.progress.value.total }} 段
            </span>
          </div>
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

      <div v-if="error" class="assistant-error" role="alert">
        <span>{{ error }}</span>
        <button v-if="failedQuestion" type="button" :disabled="busy" @click="retryFailedQuestion">重新发送</button>
      </div>
      <p v-if="speech.error.value" class="assistant-error" role="status">{{ speech.error.value }}</p>

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
