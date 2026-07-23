<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { RouterView } from "vue-router";
function applyAppearance(){ document.documentElement.classList.toggle("high-contrast", localStorage.getItem("jianda_contrast") === "1"); }
onMounted(()=>{applyAppearance();window.addEventListener("jianda-appearance-change",applyAppearance)}); onBeforeUnmount(()=>window.removeEventListener("jianda-appearance-change",applyAppearance));
</script>
<template><RouterView /></template>