<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import ContentCard from "../components/ContentCard.vue";
import { fetchItems } from "../api";
import { ArrowLeft, Search } from "lucide-vue-next";
const route = useRoute();
const query = ref("");
const items = ref<any[]>([]);
const error = ref("");
const title = computed(() =>
  route.path === "/search" ? "搜索" : String(route.params.name || "全部内容"),
);
const filtered = computed(() =>
  items.value.filter(
    (item) =>
      query.value === "" ||
      item.title.includes(query.value) ||
      item.summary.includes(query.value),
  ),
);
onMounted(async () => {
  try {
    items.value = await fetchItems(
      title.value === "搜索" || title.value === "全部内容"
        ? undefined
        : title.value,
    );
  } catch {
    error.value = "暂时无法读取内容，请稍后重试";
  }
});
</script>
<template>
  <div class="h5-page">
    <H5Header />
    <main class="h5-main list-page">
      <div class="mobile-title">
        <button @click="$router.back()"><ArrowLeft /></button>
        <h1>{{ title }}</h1>
      </div>
      <label class="search-input"
        ><Search /><input
          v-model="query"
          autofocus
          placeholder="输入您想了解的内容"
      /></label>
      <p class="result-count">找到 {{ filtered.length }} 条可靠信息</p>
      <section class="list-surface">
        <ContentCard v-for="i in filtered" :key="i.id" :item="i" />
        <div v-if="!filtered.length" class="empty">
          <Search /><b>没有找到相关内容</b>
          <p>{{ error || "换一个更简单的关键词试试" }}</p>
        </div>
      </section>
    </main>
    <BottomNav />
  </div>
</template>
