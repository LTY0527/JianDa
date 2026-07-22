<script setup lang="ts">
import { ShieldCheck, ChevronRight } from "lucide-vue-next";
defineProps<{ item: any }>();
</script>
<template>
  <RouterLink class="content-row" :to="`/${'guide'}/${item.slug}`"
    ><div>
      <span class="category-text">{{ item.category }} · {{ "办事指南" }}</span>
      <h3>{{ item.title }}</h3>
      <p>{{ item.summary }}</p>
      <footer>
        <ShieldCheck />{{ item.source || item.source_name
        }}<span
          >· {{ String(item.date || item.published_at).slice(0, 10) }}</span
        >
      </footer>
    </div>
    <ChevronRight class="row-arrow"
  /></RouterLink>
</template>
