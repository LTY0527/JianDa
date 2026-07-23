<script setup lang="ts">
import { computed } from "vue";
import { ArrowLeft, ChevronRight } from "lucide-vue-next";
import { useRoute } from "vue-router";
import { useSafeBack } from "../composables/useSafeBack";

const props = withDefaults(
  defineProps<{
    title: string;
    description?: string;
    status?: string;
    dirty?: boolean;
    backTo?: string;
    breadcrumbs?: string[];
  }>(),
  { description: "", status: "", dirty: false, backTo: "", breadcrumbs: () => [] },
);
const route = useRoute();
const showBack = computed(() => Boolean(route.meta.showBack));
const { goBack } = useSafeBack({
  fallback: computed(() => props.backTo || String(route.meta.backTo || "/")),
  isDirty: computed(() => props.dirty),
});
</script>
<template>
  <header class="page-header">
    <button
      v-if="showBack"
      type="button"
      class="page-back"
      aria-label="返回"
      @click="goBack"
    >
      <ArrowLeft /><span>返回</span>
    </button>
    <div class="page-heading">
      <nav v-if="breadcrumbs.length" class="breadcrumbs" aria-label="面包屑">
        <template v-for="(item, index) in breadcrumbs" :key="item">
          <span>{{ item }}</span><ChevronRight v-if="index < breadcrumbs.length - 1" />
        </template>
      </nav>
      <div class="page-title-line">
        <h1>{{ title }}</h1><span v-if="status" class="page-status">{{ status }}</span>
      </div>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="page-actions"><slot /></div>
  </header>
</template>