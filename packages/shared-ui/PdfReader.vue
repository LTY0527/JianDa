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
const loadNotice = ref("");
let renderTask: ReturnType<PDFPageProxy["render"]> | null = null;
let loadTask: ReturnType<typeof getDocument> | null = null;
let resizeObserver: ResizeObserver | null = null;
let touchDistance = 0;
let blobUrl = "";

class PdfHttpError extends Error {
  constructor(
    readonly status: number,
    readonly contentType = "",
  ) {
    super(`PDF HTTP ${status}`);
    this.name = "PdfHttpError";
  }
}

const canPrevious = computed(() => page.value > 1);
const canNext = computed(() => page.value < pages.value);
const scaleLabel = computed(() => `${Math.round(scale.value * 100)}%`);

async function load() {
  error.value = "";
  loadNotice.value = "";
  loading.value = true;
  renderTask?.cancel();
  await loadTask?.destroy();
  revokeBlobUrl();
  documentProxy.value = null;
  let rangeFailure: unknown;
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
    if ((cause as Error)?.name === "AbortException") return;
    rangeFailure = cause;
    await loadTask?.destroy();
    try {
      const response = await fetch(props.src, {
        headers: props.headers,
        credentials: "same-origin",
      });
      const contentType = (response.headers.get("content-type") || "")
        .split(";")[0]
        .trim()
        .toLowerCase();
      if (!response.ok) throw new PdfHttpError(response.status, contentType);
      if (contentType === "application/json" || contentType.endsWith("+json")) {
        throw new PdfHttpError(response.status, contentType);
      }
      if (
        contentType &&
        contentType !== "application/pdf" &&
        contentType !== "application/octet-stream"
      ) {
        throw new PdfHttpError(response.status, contentType);
      }
      const blob = await response.blob();
      blobUrl = URL.createObjectURL(
        blob.type ? blob : new Blob([blob], { type: "application/pdf" }),
      );
      loadTask = getDocument({ url: blobUrl });
      documentProxy.value = await loadTask.promise;
      pages.value = documentProxy.value.numPages;
      page.value = 1;
      loadNotice.value = "Range 分段加载失败，已自动切换到完整文件模式。";
      await render();
    } catch (fallbackFailure) {
      if ((fallbackFailure as Error)?.name !== "AbortException") {
        error.value = diagnoseLoadFailure(fallbackFailure, rangeFailure);
      }
    }
  } finally {
    loading.value = false;
  }
}

function revokeBlobUrl() {
  if (!blobUrl) return;
  URL.revokeObjectURL(blobUrl);
  blobUrl = "";
}

function diagnoseLoadFailure(fallback: unknown, range: unknown): string {
  if (fallback instanceof PdfHttpError) {
    if (fallback.status === 401) return "登录状态已失效，请重新登录后再试。";
    if (fallback.status === 403) return "当前机构没有权限查看这份原文件。";
    if (fallback.status === 404) return "原文件记录或存储文件不存在。";
    if (fallback.status === 416) {
      return "Range 请求异常，完整文件模式也未能取得原文件。";
    }
    if (
      fallback.contentType === "application/json" ||
      fallback.contentType.endsWith("+json")
    ) {
      return "后端返回了错误信息而不是 PDF 文件，请查看任务或服务日志。";
    }
    if (fallback.contentType) {
      return `原文件响应类型为 ${fallback.contentType}，不是可读取的 PDF。`;
    }
    return `原文件请求失败（HTTP ${fallback.status}）。`;
  }
  const candidate = fallback instanceof Error ? fallback : range;
  const name = candidate instanceof Error ? candidate.name : "";
  const message =
    candidate instanceof Error ? candidate.message.toLowerCase() : "";
  if (name === "PasswordException" || message.includes("password")) {
    return "PDF 已加密或需要密码，当前无法在线预览。";
  }
  if (name === "InvalidPDFException" || message.includes("invalid pdf")) {
    return "PDF 格式损坏或内容不完整，完整文件模式仍然无法读取。";
  }
  if (
    message.includes("worker") ||
    message.includes("fake worker") ||
    message.includes("setting up")
  ) {
    return "PDF.js Worker 加载失败，请检查前端静态资源部署。";
  }
  return "Range 分段加载失败，完整文件模式仍然无法读取 PDF。";
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
    error.value = "下载失败，请检查登录状态、文件权限或网络后重试。";
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
  revokeBlobUrl();
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
    <p v-else-if="loadNotice && !error" class="pdf-reader__notice">
      {{ loadNotice }}
    </p>
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
.pdf-reader__notice {
  margin: 0;
  padding: 0.55rem 0.85rem;
  color: #4b6074;
  background: #f8fafb;
  border-bottom: 1px solid #d7dde4;
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
