<script setup lang="ts">
defineProps<{ status: string; text: string }>();
</script>
<template>
  <span class="status" :class="status.toLowerCase()"><i></i>{{ text }}</span>
</template>
