<script setup lang="ts">
import { ref } from "vue";
import { HeartHandshake, Type, Settings, MapPin, ChevronRight, Check, X } from "lucide-vue-next";
import { useRouter } from "vue-router";
import { activeRegion, selectDachangRegion } from "../region";
const router = useRouter();
const regionOpen = ref(false);
const districts = ["黄浦区", "徐汇区", "长宁区", "静安区", "普陀区", "虹口区", "杨浦区", "浦东新区", "闵行区", "宝山区", "嘉定区", "金山区", "松江区", "青浦区", "奉贤区", "崇明区"];
const towns = ["大场镇", "友谊路街道", "张庙街道", "高境镇", "淞南镇", "顾村镇", "罗店镇", "杨行镇", "月浦镇", "罗泾镇", "庙行镇"];

function chooseDachang() {
  selectDachangRegion();
  regionOpen.value = false;
}
</script>
<template>
  <header class="h5-header">
    <RouterLink to="/" class="h5-brand" aria-label="简达首页"
      ><span><HeartHandshake /></span><b>简达</b></RouterLink
    >
    <button class="h5-region" type="button" aria-label="选择所在地区" @click="regionOpen = true">
      <MapPin />{{ activeRegion.district }} · {{ activeRegion.street_or_town }}<ChevronRight />
    </button>
    <nav>
      <button aria-label="字号设置" @click="router.push('/settings')">
        <Type />大字</button
      ><button aria-label="阅读设置" @click="router.push('/settings')">
        <Settings />设置
      </button>
    </nav>
  </header>
  <Teleport to="body">
    <div v-if="regionOpen" class="region-dialog" role="presentation" @click.self="regionOpen = false">
      <section role="dialog" aria-modal="true" aria-labelledby="region-dialog-title">
        <header><div><small>当前试点</small><h2 id="region-dialog-title">选择所在地区</h2></div><button type="button" aria-label="关闭地区选择" @click="regionOpen = false"><X /></button></header>
        <p class="region-current"><MapPin />上海市 · 宝山区 · 大场镇</p>
        <div class="region-level"><h3>上海市各区</h3><div class="region-options region-options--districts"><button v-for="district in districts" :key="district" type="button" :class="{ active: district === '宝山区' }" :disabled="district !== '宝山区'" :title="district === '宝山区' ? '当前开放区域' : '暂未开通'">{{ district }}<small v-if="district !== '宝山区'">即将开通</small><Check v-else /></button></div></div>
        <div class="region-level"><h3>宝山区街镇</h3><div class="region-options"><button v-for="town in towns" :key="town" type="button" :class="{ active: town === '大场镇' }" :disabled="town !== '大场镇'" :title="town === '大场镇' ? '进入大场镇' : '暂未开通'" @click="town === '大场镇' && chooseDachang()">{{ town }}<small v-if="town !== '大场镇'">即将开通</small><Check v-else /></button></div></div>
        <p class="region-note">当前仅开放上海市宝山区大场镇，其他地区正在逐步接入权威社区信息。</p>
      </section>
    </div>
  </Teleport>
</template>
