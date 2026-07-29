import { computed, onBeforeUnmount, ref } from "vue";

export type SpeechPlayerStatus = "idle" | "playing" | "paused";
export const SPEECH_RATES = [0.8, 1, 1.2] as const;
const CHUNK_WATCHDOG_MS = 20_000;

export function splitSpeechText(text: string, maxLength = 120): string[] {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) return [];
  const sentences = normalized.match(/[^。！？；.!?;]+[。！？；.!?;]?/g) || [normalized];
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
    } else current += sentence;
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
    SPEECH_RATES.includes(savedRate as (typeof SPEECH_RATES)[number]) ? savedRate : 1,
  );
  const chunks = ref<string[]>([]);
  const chunkIndex = ref(0);
  let utterance: SpeechSynthesisUtterance | null = null;
  let generation = 0;
  let watchdog: number | null = null;
  let watchdogRetries = 0;

  function clearWatchdog() {
    if (watchdog !== null) window.clearTimeout(watchdog);
    watchdog = null;
  }
  function loadVoices() {
    if (supported.value) voices.value = window.speechSynthesis.getVoices();
  }
  function preferredVoice() {
    return voices.value.find((voice) => /^zh-CN$/i.test(voice.lang))
      || voices.value.find((voice) => /^zh-Hans(?:-|$)/i.test(voice.lang))
      || voices.value.find((voice) => /^zh(?:-|$)/i.test(voice.lang))
      || null;
  }
  function finish(activeGeneration: number) {
    if (activeGeneration !== generation) return;
    clearWatchdog();
    status.value = "idle";
    chunkIndex.value = 0;
    utterance = null;
    watchdogRetries = 0;
    onFinished?.();
  }
  function speakCurrentChunk(activeGeneration: number) {
    if (activeGeneration !== generation) return;
    const chunk = chunks.value[chunkIndex.value];
    if (!supported.value || !chunk) {
      finish(activeGeneration);
      return;
    }
    clearWatchdog();
    utterance = new SpeechSynthesisUtterance(chunk);
    utterance.lang = "zh-CN";
    utterance.rate = rate.value;
    const voice = preferredVoice();
    if (voice) utterance.voice = voice;
    utterance.onend = () => {
      if (activeGeneration !== generation) return;
      clearWatchdog();
      watchdogRetries = 0;
      chunkIndex.value += 1;
      if (chunkIndex.value < chunks.value.length) speakCurrentChunk(activeGeneration);
      else finish(activeGeneration);
    };
    utterance.onerror = (event) => {
      if (activeGeneration !== generation || event.error === "canceled" || event.error === "interrupted") return;
      clearWatchdog();
      error.value = event.error === "not-allowed"
        ? "请点击播放按钮开始朗读。"
        : "语音播报暂时失败，请稍后重试或使用大字阅读。";
      finish(activeGeneration);
    };
    window.speechSynthesis.speak(utterance);
    status.value = "playing";
    watchdog = window.setTimeout(() => {
      if (activeGeneration !== generation || status.value !== "playing") return;
      window.speechSynthesis.cancel();
      if (watchdogRetries < 1) {
        watchdogRetries += 1;
        generation += 1;
        speakCurrentChunk(generation);
      } else {
        error.value = "本段朗读等待时间过长，请点击继续重试。";
        status.value = "paused";
      }
    }, CHUNK_WATCHDOG_MS);
  }
  function stop() {
    generation += 1;
    clearWatchdog();
    if (supported.value) window.speechSynthesis.cancel();
    chunks.value = [];
    chunkIndex.value = 0;
    utterance = null;
    watchdogRetries = 0;
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
    generation += 1;
    speakCurrentChunk(generation);
  }
  function pause() {
    if (!supported.value || status.value !== "playing") return;
    generation += 1;
    clearWatchdog();
    window.speechSynthesis.cancel();
    utterance = null;
    status.value = "paused";
  }
  function resume() {
    if (!supported.value || status.value !== "paused" || !chunks.value.length) return;
    error.value = "";
    generation += 1;
    speakCurrentChunk(generation);
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
      generation += 1;
      clearWatchdog();
      window.speechSynthesis.cancel();
      speakCurrentChunk(generation);
    }
  }
  if (supported.value) {
    loadVoices();
    window.speechSynthesis.addEventListener("voiceschanged", loadVoices);
  }
  onBeforeUnmount(() => {
    stop();
    if (supported.value) window.speechSynthesis.removeEventListener("voiceschanged", loadVoices);
  });
  return {
    supported: computed(() => supported.value),
    status,
    error,
    rate,
    progress: computed(() => ({
      current: chunks.value.length ? chunkIndex.value + 1 : 0,
      total: chunks.value.length,
    })),
    isActive: computed(() => status.value !== "idle"),
    play,
    pause,
    resume,
    toggle,
    stop,
    setRate,
  };
}
