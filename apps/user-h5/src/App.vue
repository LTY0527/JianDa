<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { RouterView } from "vue-router";
function applyAppearance(){
  const savedSize = Number(localStorage.getItem("jianda_font") || 18);
  const size = [18, 20, 22, 24].includes(savedSize) ? savedSize : 18;
  document.documentElement.style.setProperty("--reader-size", `${size}px`);
  document.documentElement.classList.toggle("high-contrast", localStorage.getItem("jianda_contrast") === "1");
}
onMounted(()=>{applyAppearance();window.addEventListener("jianda-appearance-change",applyAppearance)}); onBeforeUnmount(()=>window.removeEventListener("jianda-appearance-change",applyAppearance));
</script>
<template><RouterView /></template>
