import { computed, onBeforeUnmount, ref } from "vue";

export type SpeechPlayerStatus = "idle" | "playing" | "paused";
export const SPEECH_RATES = [0.8, 1, 1.2] as const;

export function splitSpeechText(text: string, maxLength = 160): string[] {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) return [];
  const sentences = normalized.match(/[^。！？；.!?;]+[。！？；.!?;]?/g) || [
    normalized,
  ];
  const chunks: string[] = [];
  let current = "";
  for (const sentence of sentences) {
    if (sentence.length > maxLength) {
      if (current) chunks.push(current);
      current = "";
      for (let start = 0; start < sentence.length; start += maxLength) {
        chunks.push(sentence.slice(start, start + maxLength));
      }
    } else if ((current + sentence).length > maxLength) {
      chunks.push(current);
      current = sentence;
    } else {
      current += sentence;
    }
  }
  if (current) chunks.push(current);
  return chunks;
}

export function useSpeechPlayer(onFinished?: () => void) {
  const supported = ref(
    typeof window !== "undefined" &&
      typeof window.speechSynthesis?.speak === "function" &&
      typeof window.SpeechSynthesisUtterance === "function",
  );
  const status = ref<SpeechPlayerStatus>("idle");
  const error = ref("");
  const voices = ref<SpeechSynthesisVoice[]>([]);
  const savedRate = Number(localStorage.getItem("jianda_rate") || 1);
  const rate = ref<number>(
    SPEECH_RATES.includes(savedRate as (typeof SPEECH_RATES)[number])
      ? savedRate
      : 1,
  );
  const chunks = ref<string[]>([]);
  const chunkIndex = ref(0);
  let utterance: SpeechSynthesisUtterance | null = null;

  function loadVoices() {
    if (!supported.value) return;
    voices.value = window.speechSynthesis.getVoices();
  }

  function preferredVoice() {
    return (
      voices.value.find((voice) => /^zh-CN$/i.test(voice.lang)) ||
      voices.value.find((voice) => /^zh/i.test(voice.lang)) ||
      null
    );
  }

  function finish() {
    status.value = "idle";
    chunkIndex.value = 0;
    utterance = null;
    onFinished?.();
  }

  function speakCurrentChunk() {
    if (!supported.value || !chunks.value[chunkIndex.value]) {
      finish();
      return;
    }
    utterance = new SpeechSynthesisUtterance(chunks.value[chunkIndex.value]);
    utterance.lang = "zh-CN";
    utterance.rate = rate.value;
    const voice = preferredVoice();
    if (voice) utterance.voice = voice;
    utterance.onend = () => {
      chunkIndex.value += 1;
      if (chunkIndex.value < chunks.value.length) speakCurrentChunk();
      else finish();
    };
    utterance.onerror = (event) => {
      if (event.error === "canceled" || event.error === "interrupted") return;
      error.value =
        event.error === "not-allowed"
          ? "浏览器需要您点击播放按钮后才能开始播报。"
          : "语音播报暂时失败，请稍后重试或使用大字阅读。";
      finish();
    };
    window.speechSynthesis.speak(utterance);
    status.value = "playing";
  }

  function stop() {
    if (supported.value) window.speechSynthesis.cancel();
    chunks.value = [];
    chunkIndex.value = 0;
    utterance = null;
    status.value = "idle";
  }

  function play(text: string) {
    error.value = "";
    if (!supported.value) {
      error.value = "当前浏览器不支持语音播报，您仍可使用大字阅读。";
      return;
    }
    stop();
    chunks.value = splitSpeechText(text);
    if (!chunks.value.length) {
      error.value = "当前没有可播报的通俗内容。";
      return;
    }
    loadVoices();
    speakCurrentChunk();
  }

  function pause() {
    if (!supported.value || status.value !== "playing") return;
    window.speechSynthesis.pause();
    status.value = "paused";
  }

  function resume() {
    if (!supported.value || status.value !== "paused") return;
    window.speechSynthesis.resume();
    status.value = "playing";
  }

  function toggle(text: string) {
    if (status.value === "playing") pause();
    else if (status.value === "paused") resume();
    else play(text);
  }

  function setRate(value: number) {
    if (!SPEECH_RATES.includes(value as (typeof SPEECH_RATES)[number])) return;
    rate.value = value;
    localStorage.setItem("jianda_rate", String(value));
    if (status.value === "playing" && chunks.value.length) {
      window.speechSynthesis.cancel();
      speakCurrentChunk();
    }
  }

  if (supported.value) {
    loadVoices();
    window.speechSynthesis.addEventListener("voiceschanged", loadVoices);
  }

  onBeforeUnmount(() => {
    stop();
    if (supported.value) {
      window.speechSynthesis.removeEventListener("voiceschanged", loadVoices);
    }
  });

  return {
    supported: computed(() => supported.value),
    status,
    error,
    rate,
    isActive: computed(() => status.value !== "idle"),
    play,
    pause,
    resume,
    toggle,
    stop,
    setRate,
  };
}
