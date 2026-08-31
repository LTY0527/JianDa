<script setup lang="ts">
import { MapPin, Check } from "lucide-vue-next";
import { townRegions } from "../utils/regions";

defineProps<{
  modelValue: string;
  disabled?: boolean;
  label?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
</script>

<template>
  <fieldset class="town-region-selector">
    <legend>{{ label || "内容适用地区" }} <b aria-hidden="true">*</b></legend>
    <p><MapPin />请选择该材料主要服务的街镇</p>
    <div>
      <button
        v-for="region in townRegions"
        :key="region.code"
        type="button"
        :disabled="disabled"
        :class="{ active: modelValue === region.code }"
        :aria-pressed="modelValue === region.code"
        @click="emit('update:modelValue', region.code)"
      >
        {{ region.name }}
        <Check v-if="modelValue === region.code" aria-hidden="true" />
      </button>
    </div>
    <small v-if="!modelValue">未选择地区前不会上传、调用 AI 或创建处理任务。</small>
  </fieldset>
</template>

<style scoped>
.town-region-selector{margin:0 0 22px;padding:0;border:0}.town-region-selector legend{font-size:13px;font-weight:700}.town-region-selector legend b{color:var(--color-danger)}.town-region-selector>p{display:flex;align-items:center;gap:6px;margin:8px 0 12px;color:var(--color-muted);font-size:12px}.town-region-selector>p svg{width:16px;height:16px;color:var(--color-primary)}.town-region-selector>div{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.town-region-selector button{min-height:52px;display:flex;align-items:center;justify-content:center;gap:7px;border:1px solid var(--color-border);border-radius:8px;background:#fff;cursor:pointer;font-weight:700}.town-region-selector button:hover{border-color:#8fb1aa}.town-region-selector button.active{border:2px solid var(--color-primary);background:var(--color-primary-soft);color:var(--color-primary)}.town-region-selector button svg{width:17px}.town-region-selector button:disabled{cursor:not-allowed;opacity:.55}.town-region-selector small{display:block;margin-top:9px;color:#9a5716;line-height:1.5}@media(max-width:560px){.town-region-selector>div{grid-template-columns:1fr}.town-region-selector button{min-height:48px}}
</style>
