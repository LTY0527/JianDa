<script setup lang="ts">
import { SPEECH_RATES } from "../composables/useSpeechPlayer";

defineProps<{ modelValue: number }>();
const emit = defineEmits<{ select: [value: number] }>();
</script>

<template>
  <div class="speech-rate-selector" role="group" aria-label="语音播报速度">
    <button
      v-for="value in SPEECH_RATES"
      :key="value"
      type="button"
      :class="{ active: modelValue === value }"
      :aria-pressed="modelValue === value"
      @click="emit('select', value)"
    >
      {{ value.toFixed(1) }}×
    </button>
  </div>
</template>
