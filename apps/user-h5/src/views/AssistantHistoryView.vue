<script setup lang="ts">
import { computed, ref } from "vue";
import AppTopBar from "../components/navigation/AppTopBar.vue";
import { Clock3, MessageCircleQuestion } from "lucide-vue-next";

interface StoredMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  createdAt: string;
}

const messages = ref<StoredMessage[]>(readMessages());
const exchanges = computed(() => {
  const result: Array<{ id: string; question: string; answer: string; createdAt: string }> = [];
  for (let index = 0; index < messages.value.length; index += 1) {
    const message = messages.value[index];
    if (message.role !== "user") continue;
    const reply = messages.value.slice(index + 1).find((item) => item.role === "assistant");
    result.push({ id: message.id, question: message.text, answer: reply?.text || "尚未收到回答", createdAt: message.createdAt });
  }
  return result.reverse();
});

function readMessages() {
  try { return JSON.parse(localStorage.getItem("jianda_assistant_session") || "[]") as StoredMessage[]; }
  catch { return []; }
}
function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
</script>

<template>
  <div class="h5-page">
    <AppTopBar title="历史会话" />
    <main class="h5-main assistant-history-page">
      <header class="library-head"><div><h1>本机会话记录</h1><p>仅保存在当前浏览器，清空助手会话后会同步移除。</p></div></header>
      <section v-if="exchanges.length" class="assistant-history-list">
        <article v-for="item in exchanges" :key="item.id">
          <small>{{ formatTime(item.createdAt) }}</small><h2>{{ item.question }}</h2><p>{{ item.answer }}</p>
          <RouterLink to="/assistant"><MessageCircleQuestion />继续询问</RouterLink>
        </article>
      </section>
      <section v-else class="state-block assistant-history-empty"><Clock3 /><h2>还没有历史会话</h2><p>从简达助手提出问题后，问答会保存在这里。</p><RouterLink to="/assistant">去问一个问题</RouterLink></section>
    </main>
  </div>
</template>

<style scoped>
.assistant-history-empty a {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 8px 14px;
}
</style>
