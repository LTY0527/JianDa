<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { CalendarDays, ChevronRight, Clock3, ExternalLink, HeartPulse, MapPin, Phone, PhoneCall, Utensils, ClipboardList, WifiOff } from "lucide-vue-next";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { fetchItems, fetchServiceDirectory, type PublicItem, type ServiceDirectoryItem } from "../api";
import { activeRegion } from "../region";
import { contentKind, truncateSummary } from "../content";
import { buildTelephoneHref } from "../utils/contactActions";

const route = useRoute();
const items = ref<PublicItem[]>([]);
const directory = ref<ServiceDirectoryItem[]>([]);
const loading = ref(true);
const error = ref("");
const kind = computed(() => String(route.meta.serviceKind || "guides"));
const configs = {
  health: { title: "社区卫生", intro: "查找当前地区已核验的卫生服务、体检和健康提醒。", icon: HeartPulse, pattern: /健康|卫生|医疗|体检|疫苗|医院/ },
  meals: { title: "长者食堂", intro: "查找助餐点、用餐安排和适老助餐信息。", icon: Utensils, pattern: /助餐|食堂|用餐|餐饮/ },
  contacts: { title: "便民电话", intro: "电话独立展示，点击即可拨号；全部来自已核验来源。", icon: PhoneCall, pattern: /./ },
  activities: { title: "活动报名", intro: "查看当前地区近期活动、日期、地点和报名入口。", icon: CalendarDays, pattern: /活动|报名|讲座|课堂|开放日/ },
  guides: { title: "办事指南", intro: "按适用对象、办理期限、材料和步骤查看已审核指南。", icon: ClipboardList, pattern: /办事|办理|材料|申请|换领/ },
} as const;
const config = computed(() => configs[kind.value as keyof typeof configs] || configs.guides);
const relevantItems = computed(() => items.value.filter((item) => {
  if (kind.value === "guides") return contentKind(item) === "guide" || config.value.pattern.test(`${item.title}${item.summary}${item.category}`);
  return config.value.pattern.test(`${item.title}${item.summary}${item.category}`);
}));
const relevantDirectory = computed(() => directory.value.filter((item) => {
  const text = `${item.name}${item.description}${item.service_type}`;
  if (kind.value === "contacts") return Boolean(item.phone);
  return config.value.pattern.test(text);
}));

async function load() {
  loading.value = true; error.value = "";
  try {
    [items.value, directory.value] = await Promise.all([
      fetchItems(undefined, activeRegion.value.region_code),
      fetchServiceDirectory(activeRegion.value.region_code),
    ]);
  } catch { error.value = "当前服务信息暂时无法读取，请稍后重试。"; }
  finally { loading.value = false; }
}
onMounted(load);
watch(() => activeRegion.value.region_code, load);
</script>

<template>
  <div class="h5-page"><H5Header />
    <main class="h5-main service-channel-page">
      <header class="service-channel-head"><component :is="config.icon" /><div><p>{{ activeRegion.street_or_town }}</p><h1>{{ config.title }}</h1><span>{{ config.intro }}</span></div></header>
      <div v-if="loading" class="list-skeleton"><i v-for="n in 4" :key="n"></i></div>
      <div v-else-if="error" class="state-block"><WifiOff /><h2>服务信息未加载</h2><p>{{ error }}</p><button @click="load">重新加载</button></div>
      <template v-else>
        <section v-if="relevantDirectory.length" class="service-channel-section">
          <header><h2>{{ kind === 'contacts' ? '常用电话' : '附近实体服务' }}</h2><span>{{ relevantDirectory.length }} 项已核验</span></header>
          <article v-for="entry in relevantDirectory" :key="entry.id" class="service-place-row">
            <div><small>{{ entry.service_type }}</small><h3>{{ entry.name }}</h3><p>{{ entry.description }}</p></div>
            <dl>
              <div v-if="entry.address"><dt><MapPin />地址</dt><dd>{{ entry.address }}</dd></div>
              <div v-if="entry.phone"><dt><Phone />电话</dt><dd><a :href="buildTelephoneHref(entry.phone)">{{ entry.phone }}</a></dd></div>
              <div v-if="entry.opening_hours"><dt><Clock3 />时间</dt><dd>{{ entry.opening_hours }}</dd></div>
            </dl>
            <a :href="entry.source_url" target="_blank" rel="noopener noreferrer">查看官方来源<ExternalLink /></a>
          </article>
        </section>
        <section v-if="relevantItems.length" class="service-channel-section">
          <header><h2>{{ kind === 'activities' ? '近期活动' : kind === 'guides' ? '办理指南' : '相关通知' }}</h2><span>{{ relevantItems.length }} 篇已审核</span></header>
          <RouterLink v-for="item in relevantItems" :key="item.id" :to="`/${contentKind(item)}/${item.slug}`" class="service-content-row">
            <div><small>{{ item.category }} · {{ item.source_name }}</small><h3>{{ item.title }}</h3><p>{{ truncateSummary(item.summary, 90) }}</p><time>{{ String(item.deadline_at || item.published_at).slice(0,10) }}</time></div><ChevronRight />
          </RouterLink>
        </section>
        <section v-if="!relevantDirectory.length && !relevantItems.length" class="state-block"><component :is="config.icon" /><h2>当前地区暂无已核验内容</h2><p>平台不会用演示电话、地址或活动补位。可以切换地区或稍后再看。</p></section>
      </template>
    </main><BottomNav />
  </div>
</template>
