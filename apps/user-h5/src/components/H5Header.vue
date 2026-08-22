<script setup lang="ts">
import { HeartHandshake, Type, Settings, MapPin } from "lucide-vue-next";
import { useRouter } from "vue-router";
import { activeRegion, selectDachangRegion } from "../region";
const router = useRouter();
</script>
<template>
  <header class="h5-header">
    <RouterLink to="/" class="h5-brand" aria-label="简达首页"
      ><span><HeartHandshake /></span><b>简达</b></RouterLink
    >
    <button class="h5-region" type="button" aria-label="选择所在地区" @click="selectDachangRegion">
      <MapPin />{{ activeRegion.street_or_town }}
    </button>
    <nav>
      <button aria-label="字号设置" @click="router.push('/settings')">
        <Type />大字</button
      ><button aria-label="阅读设置" @click="router.push('/settings')">
        <Settings />设置
      </button>
    </nav>
  </header>
</template>
