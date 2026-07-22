<script setup lang="ts">
import { ShieldX } from "lucide-vue-next";
</script>

<template>
  <section class="permission-state">
    <ShieldX :size="48" />
    <h1>无权访问此页面</h1>
    <p>权威来源与公开信息导入仅对平台管理员开放。</p>
    <RouterLink class="btn primary" to="/">返回工作台</RouterLink>
  </section>
</template>
