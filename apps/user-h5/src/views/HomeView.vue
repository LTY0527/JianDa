<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import {
  fetchItems,
  fetchServiceDirectory,
  type PublicItem,
  type ServiceDirectoryItem,
} from "../api";
import { contentKind, importanceScore, normalizeTitle, truncateSummary } from "../content";
import { readerPreferences } from "../library";
import { activeRegion } from "../region";
import { articleCover, hasRealCover, realCoverScore } from "../utils/coverImage";
import {
  ArrowRight,
  BellRing,
  Building2,
  CalendarDays,
  ChevronRight,
  CircleHelp,
  HeartPulse,
  MapPin,
  Phone,
  PhoneCall,
  Search,
  ShieldAlert,
  Sparkles,
  Utensils,
  WifiOff,
} from "lucide-vue-next";

type ChannelKey = "recommend" | "dachang" | "health" | "elderly" | "services" | "fraud" | "activity" | "community";
type FeedKind = "image" | "alert" | "service" | "activity" | "text";

const route = useRoute();
const router = useRouter();
const items = ref<PublicItem[]>([]);
const directory = ref<ServiceDirectoryItem[]>([]);
const loading = ref(true);
const error = ref("");
const featuredCoverFailed = ref(false);

const channels: Array<{ key: ChannelKey; label: string }> = [
  { key: "recommend", label: "推荐" },
  { key: "dachang", label: "大场" },
  { key: "health", label: "健康" },
  { key: "elderly", label: "养老" },
  { key: "services", label: "办事" },
  { key: "fraud", label: "防诈" },
  { key: "activity", label: "活动" },
  { key: "community", label: "社区" },
];
const selectedChannel = computed<ChannelKey>(() => {
  const value = String(route.query.channel || "recommend") as ChannelKey;
  return channels.some((channel) => channel.key === value) ? value : "recommend";
});

const commonServices = [
  ["社区卫生", HeartPulse, "健康"],
  ["长者食堂", Utensils, "养老"],
  ["便民电话", PhoneCall, "生活服务"],
  ["活动报名", CalendarDays, "活动"],
  ["办事指南", Building2, "办事"],
] as const;

function preferredScore(item: PublicItem) {
  const preferred = readerPreferences().channels.map((value) =>
    value === "政策" ? "养老政策" : value === "生活" ? "社区服务" : value,
  );
  return importanceScore(item) + (preferred.includes(item.category) ? 12 : 0);
}
function includesText(item: PublicItem, pattern: RegExp) {
  return pattern.test(`${item.title} ${item.summary} ${item.category}`);
}
function isLocal(item: PublicItem) {
  return Boolean(item.is_local || item.region_code === activeRegion.value.region_code);
}
function channelMatches(item: PublicItem, channel: ChannelKey) {
  if (channel === "recommend") return true;
  if (channel === "dachang") return isLocal(item);
  if (channel === "health") return includesText(item, /健康|卫生|医疗|体检|疫苗|医院/);
  if (channel === "elderly") return includesText(item, /养老|助老|长者|老年|银龄|助餐/);
  if (channel === "services") return contentKind(item) === "guide" || includesText(item, /办事|办理|材料|服务|换领/);
  if (channel === "fraud") return includesText(item, /反诈|诈骗|银行卡|验证码|风险/);
  if (channel === "activity") return includesText(item, /活动|报名|开放日|讲座|辅导|场次/);
  return includesText(item, /社区|大场|街道|邻里|便民/);
}
function rank(item: PublicItem) {
  const deadlineBoost = item.deadline_at ? 18 : 0;
  const localBoost = isLocal(item) ? 16 : 0;
  return preferredScore(item) + deadlineBoost + localBoost + realCoverScore(item);
}
const channelItems = computed(() =>
  [...items.value]
    .filter((item) => channelMatches(item, selectedChannel.value))
    .sort((a, b) => rank(b) - rank(a)),
);
const featured = computed(() => channelItems.value[0]);
const featuredHasVisual = computed(() =>
  Boolean(featured.value && hasRealCover(featured.value) && !featuredCoverFailed.value),
);
const feedItems = computed(() => channelItems.value.filter((item) => item.id !== featured.value?.id).slice(0, 12));
const selectedChannelLabel = computed(() => channels.find((channel) => channel.key === selectedChannel.value)?.label || "推荐");

function feedKind(item: PublicItem): FeedKind {
  if (hasRealCover(item)) return "image";
  if (includesText(item, /活动|报名|开放日|讲座|辅导|场次/)) return "activity";
  if (contentKind(item) === "guide") return "service";
  if (/紧急|重要提醒|反诈|诈骗|截止|暂停|风险提示/.test(item.title)) return "alert";
  return "text";
}
function detailPath(item: PublicItem) {
  return `/${contentKind(item)}/${item.slug}`;
}
function shortDate(value?: string) {
  return value ? String(value).slice(0, 10) : "";
}
function hideBrokenImage(event: Event) {
  (event.currentTarget as HTMLImageElement).hidden = true;
}
function selectChannel(key: ChannelKey) {
  featuredCoverFailed.value = false;
  void router.replace({ path: "/", query: key === "recommend" ? {} : { channel: key } });
}
async function load() {
  loading.value = true;
  error.value = "";
  featuredCoverFailed.value = false;
  try {
    const [published, services] = await Promise.all([
      fetchItems(undefined, activeRegion.value.region_code),
      fetchServiceDirectory(activeRegion.value.region_code),
    ]);
    items.value = published;
    directory.value = services;
  } catch {
    error.value = "暂时无法读取权威内容，请稍后再试";
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <div class="h5-page h5-home">
    <H5Header />
    <main class="h5-main commercial-home">
      <section class="home-discovery">
        <div class="home-discovery__brand">
          <div><strong>简达</strong><span><MapPin />宝山区 · {{ activeRegion.street_or_town }}</span></div>
          <p>权威内容，人工核对后发布</p>
        </div>
        <RouterLink to="/search" class="home-search" aria-label="搜索通知、办事和社区服务">
          <Search /><span>搜索通知、办事和社区服务</span><b>搜索</b>
        </RouterLink>
      </section>

      <nav class="home-channels" aria-label="首页频道">
        <button
          v-for="channel in channels"
          :key="channel.key"
          type="button"
          :class="{ active: selectedChannel === channel.key }"
          :aria-current="selectedChannel === channel.key ? 'page' : undefined"
          @click="selectChannel(channel.key)"
        >{{ channel.label }}</button>
      </nav>

      <div v-if="loading" class="home-skeleton" aria-label="正在加载"><i v-for="n in 5" :key="n"></i></div>
      <div v-else-if="error" class="home-error" role="status">
        <WifiOff /><div><b>内容暂时没有加载成功</b><p>{{ error }}</p></div>
        <button type="button" @click="load">重新加载</button>
      </div>

      <template v-else>
        <section v-if="featured" class="commercial-hero" :class="{ 'commercial-hero--text': !featuredHasVisual }">
          <img
            v-if="featuredHasVisual"
            :src="articleCover(featured)"
            :alt="featured.image_alt_text || `${featured.title}配图`"
            fetchpriority="high"
            decoding="async"
            referrerpolicy="no-referrer"
            @error="featuredCoverFailed = true"
          />
          <div class="commercial-hero__body">
            <span>{{ featured.category }}<template v-if="isLocal(featured)"> · 大场</template></span>
            <h1>{{ normalizeTitle(featured.title) }}</h1>
            <p>{{ truncateSummary(featured.summary, 126) }}</p>
            <small>{{ featured.source_name }} · {{ shortDate(featured.published_at) }}</small>
            <RouterLink :to="detailPath(featured)">立即查看<ArrowRight /></RouterLink>
          </div>
        </section>

        <nav class="quick-tasks" aria-label="高频服务">
          <RouterLink
            v-for="task in commonServices"
            :key="task[0]"
            :to="{ path: '/services', query: { type: task[2] } }"
          ><span><component :is="task[1]" /></span><b>{{ task[0] }}</b></RouterLink>
        </nav>

        <section class="mixed-feed">
          <header class="mixed-feed__heading">
            <div><h2>{{ selectedChannelLabel }}内容</h2><p>来自已审核发布的权威信息</p></div>
            <RouterLink to="/news">查看全部<ChevronRight /></RouterLink>
          </header>

          <article v-if="selectedChannel === 'services' && directory.length" class="feed-entry feed-entry--directory">
            <span class="feed-entry__icon"><Building2 /></span>
            <div>
              <small>{{ directory[0].service_type }}</small>
              <h3>{{ directory[0].name }}</h3>
              <p>{{ directory[0].description }}</p>
              <dl>
                <div v-if="directory[0].address"><dt><MapPin />地址</dt><dd>{{ directory[0].address }}</dd></div>
                <div v-if="directory[0].phone"><dt><Phone />电话</dt><dd>{{ directory[0].phone }}</dd></div>
                <div v-if="directory[0].opening_hours"><dt><CalendarDays />时间</dt><dd>{{ directory[0].opening_hours }}</dd></div>
              </dl>
            </div>
            <RouterLink to="/services">查看服务<ChevronRight /></RouterLink>
          </article>

          <RouterLink
            v-for="item in feedItems"
            :key="item.id"
            :to="detailPath(item)"
            class="feed-entry"
            :class="`feed-entry--${feedKind(item)}`"
          >
            <img
              v-if="feedKind(item) === 'image'"
              :src="articleCover(item)"
              :alt="item.image_alt_text || `${item.title}配图`"
              loading="lazy"
              decoding="async"
              referrerpolicy="no-referrer"
              @error="hideBrokenImage"
            />
            <span v-else-if="feedKind(item) === 'alert'" class="feed-entry__icon"><BellRing /></span>
            <span v-else-if="feedKind(item) === 'activity'" class="feed-entry__date">
              <b>{{ shortDate(item.deadline_at || item.effective_from || item.published_at).slice(5) }}</b>
              <small>{{ item.deadline_at ? "截止" : "活动" }}</small>
            </span>
            <span v-else-if="feedKind(item) === 'service'" class="feed-entry__icon"><Building2 /></span>
            <div class="feed-entry__body">
              <small>{{ item.category }}<template v-if="isLocal(item)"> · 大场</template></small>
              <h3>{{ normalizeTitle(item.title) }}</h3>
              <p>{{ truncateSummary(item.summary, feedKind(item) === "image" ? 72 : 110) }}</p>
              <footer>{{ item.source_name }} · {{ shortDate(item.published_at) }}</footer>
            </div>
            <ChevronRight class="feed-entry__arrow" />
          </RouterLink>

          <div v-if="!feedItems.length && !(selectedChannel === 'services' && directory.length)" class="channel-empty">
            <CircleHelp /><b>当前频道暂无更多已审核内容</b>
            <p>可以切换其他频道，或让简达根据平台资料帮您查找。</p>
            <RouterLink :to="{ path: '/assistant', query: { q: selectedChannelLabel } }">问简达<Sparkles /></RouterLink>
          </div>
        </section>
      </template>
    </main>
    <BottomNav />
  </div>
</template>
