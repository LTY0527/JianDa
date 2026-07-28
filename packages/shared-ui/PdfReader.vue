<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from "vue";
import {
  GlobalWorkerOptions,
  getDocument,
  type PDFDocumentProxy,
  type PDFPageProxy,
} from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

GlobalWorkerOptions.workerSrc = workerUrl;

const props = withDefaults(
  defineProps<{
    src: string;
    downloadUrl?: string;
    filename?: string;
    headers?: Record<string, string>;
  }>(),
  { downloadUrl: "", filename: "原文件.pdf", headers: () => ({}) },
);

const host = ref<HTMLElement | null>(null);
const canvas = ref<HTMLCanvasElement | null>(null);
// PDF.js document proxies use JavaScript private fields and must not be wrapped
// in Vue's deep reactive Proxy.
const documentProxy = shallowRef<PDFDocumentProxy | null>(null);
const page = ref(1);
const pages = ref(0);
const scale = ref(1);
const fitWidth = ref(true);
const loading = ref(true);
const error = ref("");
let renderTask: ReturnType<PDFPageProxy["render"]> | null = null;
let loadTask: ReturnType<typeof getDocument> | null = null;
let resizeObserver: ResizeObserver | null = null;
let touchDistance = 0;

const canPrevious = computed(() => page.value > 1);
const canNext = computed(() => page.value < pages.value);
const scaleLabel = computed(() => `${Math.round(scale.value * 100)}%`);

async function load() {
  error.value = "";
  loading.value = true;
  renderTask?.cancel();
  await loadTask?.destroy();
  documentProxy.value = null;
  try {
    loadTask = getDocument({
      url: props.src,
      httpHeaders: props.headers,
      withCredentials: false,
    });
    documentProxy.value = await loadTask.promise;
    pages.value = documentProxy.value.numPages;
    page.value = 1;
    await render();
  } catch (cause) {
    if ((cause as Error)?.name !== "AbortException") {
      error.value = "PDF 加载失败，请检查网络后重试。";
    }
  } finally {
    loading.value = false;
  }
}

async function render() {
  const pdf = documentProxy.value;
  const target = canvas.value;
  const container = host.value;
  if (!pdf || !target || !container) return;
  renderTask?.cancel();
  const pdfPage = await pdf.getPage(page.value);
  const base = pdfPage.getViewport({ scale: 1 });
  const available = Math.max(280, container.clientWidth - 32);
  const effectiveScale = fitWidth.value ? available / base.width : scale.value;
  const viewport = pdfPage.getViewport({ scale: effectiveScale });
  const outputScale = Math.min(window.devicePixelRatio || 1, 2);
  const context = target.getContext("2d");
  if (!context) throw new Error("Canvas unavailable");
  target.width = Math.floor(viewport.width * outputScale);
  target.height = Math.floor(viewport.height * outputScale);
  target.style.width = `${Math.floor(viewport.width)}px`;
  target.style.height = `${Math.floor(viewport.height)}px`;
  renderTask = pdfPage.render({
    canvasContext: context,
    viewport,
    transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
  });
  try {
    await renderTask.promise;
  } catch (cause) {
    if ((cause as Error)?.name !== "RenderingCancelledException") throw cause;
  }
}

async function go(delta: number) {
  page.value = Math.min(pages.value, Math.max(1, page.value + delta));
  await nextTick();
  await render();
  host.value?.scrollTo({ top: 0, behavior: "smooth" });
}

async function zoom(delta: number) {
  fitWidth.value = false;
  scale.value = Math.min(2.5, Math.max(0.5, scale.value + delta));
  await render();
}

async function useFitWidth() {
  fitWidth.value = true;
  await render();
}

async function toggleFullscreen() {
  const element = host.value?.closest(".pdf-reader") as HTMLElement | null;
  if (!element) return;
  if (document.fullscreenElement) await document.exitFullscreen();
  else await element.requestFullscreen();
}

async function download() {
  try {
    const response = await fetch(props.downloadUrl || `${props.src}?download=true`, {
      headers: props.headers,
    });
    if (!response.ok) throw new Error(String(response.status));
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = props.filename;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch {
    error.value = "下载失败，请稍后重试。";
  }
}

function touchStart(event: TouchEvent) {
  if (event.touches.length === 2) {
    touchDistance = Math.hypot(
      event.touches[0].clientX - event.touches[1].clientX,
      event.touches[0].clientY - event.touches[1].clientY,
    );
  }
}

function touchEnd(event: TouchEvent) {
  if (touchDistance && event.touches.length < 2) touchDistance = 0;
}

function touchMove(event: TouchEvent) {
  if (event.touches.length !== 2 || !touchDistance) return;
  const distance = Math.hypot(
    event.touches[0].clientX - event.touches[1].clientX,
    event.touches[0].clientY - event.touches[1].clientY,
  );
  if (Math.abs(distance - touchDistance) > 24) {
    void zoom(distance > touchDistance ? 0.1 : -0.1);
    touchDistance = distance;
  }
}

watch(() => props.src, load);
watch(page, render);
onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    if (fitWidth.value) void render();
  });
  if (host.value) resizeObserver.observe(host.value);
  void load();
});
onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  renderTask?.cancel();
  void loadTask?.destroy();
});
</script>

<template>
  <section class="pdf-reader" aria-label="PDF 在线阅读器">
    <div class="pdf-reader__toolbar">
      <button type="button" :disabled="!canPrevious" @click="go(-1)">上一页</button>
      <span>第 {{ page }} / {{ pages || "—" }} 页</span>
      <button type="button" :disabled="!canNext" @click="go(1)">下一页</button>
      <button type="button" @click="zoom(-0.15)">缩小</button>
      <span>{{ fitWidth ? "适合宽度" : scaleLabel }}</span>
      <button type="button" @click="zoom(0.15)">放大</button>
      <button type="button" @click="useFitWidth">适合宽度</button>
      <button type="button" @click="toggleFullscreen">全屏</button>
      <button type="button" @click="download">下载原文件</button>
    </div>
    <p v-if="loading" class="pdf-reader__state">正在加载 PDF…</p>
    <div v-else-if="error" class="pdf-reader__state pdf-reader__error">
      <p>{{ error }}</p>
      <button type="button" @click="load">重新加载</button>
    </div>
    <div
      v-show="!loading && !error"
      ref="host"
      class="pdf-reader__viewport"
      @touchstart.passive="touchStart"
      @touchmove.passive="touchMove"
      @touchend.passive="touchEnd"
    >
      <canvas ref="canvas"></canvas>
    </div>
  </section>
</template>

<style scoped>
.pdf-reader {
  display: grid;
  min-height: 32rem;
  background: #eef1f4;
  border: 1px solid #d7dde4;
  border-radius: 10px;
  overflow: hidden;
}
.pdf-reader__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  padding: 0.75rem;
  background: #fff;
  border-bottom: 1px solid #d7dde4;
}
.pdf-reader__toolbar button,
.pdf-reader__state button {
  min-height: 40px;
  padding: 0.45rem 0.75rem;
  color: #17324d;
  background: #fff;
  border: 1px solid #aebac7;
  border-radius: 6px;
  cursor: pointer;
}
.pdf-reader__toolbar button:disabled {
  opacity: 0.45;
  cursor: default;
}
.pdf-reader__viewport {
  overflow: auto;
  display: grid;
  justify-items: center;
  align-items: start;
  padding: 1rem;
  max-height: 72vh;
  touch-action: pan-x pan-y;
}
.pdf-reader__viewport canvas {
  max-width: none;
  background: #fff;
  box-shadow: 0 2px 14px rgb(25 42 58 / 16%);
}
.pdf-reader__state {
  align-self: center;
  justify-self: center;
  padding: 2rem;
  text-align: center;
}
.pdf-reader__error {
  color: #9d2f2f;
}
.pdf-reader:fullscreen {
  height: 100vh;
  border-radius: 0;
}
.pdf-reader:fullscreen .pdf-reader__viewport {
  max-height: calc(100vh - 72px);
}
@media (max-width: 640px) {
  .pdf-reader {
    min-height: 26rem;
    border-radius: 0;
  }
  .pdf-reader__toolbar {
    gap: 0.35rem;
    padding: 0.5rem;
  }
  .pdf-reader__toolbar button {
    min-height: 44px;
    padding-inline: 0.6rem;
  }
  .pdf-reader__viewport {
    padding: 0.5rem;
    max-height: 68vh;
  }
}
</style>
