<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from "vue";
import { CircleHelp, X } from "lucide-vue-next";
import { helpGlossary, type HelpGlossaryKey } from "../helpGlossary";

const props = defineProps<{ term: HelpGlossaryKey; label?: string }>();
const open = ref(false);
const trigger = ref<HTMLButtonElement | null>(null);
const position = ref({ top: 0, left: 0 });
const tipId = `help-tip-${Math.random().toString(36).slice(2, 10)}`;
const text = computed(() => helpGlossary[props.term]);

function place() {
  const rect = trigger.value?.getBoundingClientRect();
  if (!rect) return;
  const width = Math.min(310, window.innerWidth - 24);
  position.value = {
    top: Math.min(window.innerHeight - 120, rect.bottom + 8),
    left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
  };
}
function close() {
  open.value = false;
  document.removeEventListener("pointerdown", outside, true);
  window.removeEventListener("resize", place);
  window.removeEventListener("scroll", place, true);
}
function show() {
  open.value = true;
  void nextTick(place);
  document.addEventListener("pointerdown", outside, true);
  window.addEventListener("resize", place);
  window.addEventListener("scroll", place, true);
}
function outside(event: Event) {
  const target = event.target as Node;
  if (!trigger.value?.contains(target) && !document.getElementById(tipId)?.contains(target)) close();
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") { close(); trigger.value?.focus(); }
  if (event.key === "Enter" || event.key === " ") { event.preventDefault(); show(); }
}
onBeforeUnmount(close);
</script>

<template>
  <span class="help-tip-wrap">
    <button ref="trigger" type="button" class="help-tip-trigger" :aria-label="label || '查看说明'"
      :aria-expanded="open" :aria-describedby="open ? tipId : undefined" @click="show" @keydown="keydown"
      @mouseenter="show" @mouseleave="close" @focus="show"><CircleHelp /></button>
    <Teleport to="body">
      <aside v-if="open" :id="tipId" class="help-tip-popover" role="tooltip"
        :style="{ top: `${position.top}px`, left: `${position.left}px` }" @mouseenter="show" @mouseleave="close">
        <p>{{ text }}</p><button type="button" aria-label="关闭说明" @click="close"><X /></button>
      </aside>
    </Teleport>
  </span>
</template>
