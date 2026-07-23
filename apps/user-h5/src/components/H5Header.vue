<script setup lang="ts">
import { HeartHandshake, Type, Settings } from "lucide-vue-next";
import { useRouter } from "vue-router";
const router = useRouter();
</script>
<template>
  <header class="h5-header">
    <RouterLink to="/" class="h5-brand" aria-label="简达首页"
      ><span><HeartHandshake /></span><b>简达</b></RouterLink
    >
    <nav>
      <button aria-label="字号设置" @click="router.push('/settings')">
        <Type />大字</button
      ><button aria-label="阅读设置" @click="router.push('/settings')">
        <Settings />设置
      </button>
    </nav>
  </header>
</template>
