<script setup lang="ts">
import { ref } from "vue";
import { HeartHandshake, Type, Settings, MapPin, ChevronRight, Check, X } from "lucide-vue-next";
import { useRouter } from "vue-router";
import { activeRegion, selectRegion, supportedRegions } from "../region";
const router = useRouter();
const regionOpen = ref(false);
const districts = ["黄浦区", "徐汇区", "长宁区", "静安区", "普陀区", "虹口区", "杨浦区", "浦东新区", "闵行区", "宝山区", "嘉定区", "金山区", "松江区", "青浦区", "奉贤区", "崇明区"];
const towns = ["大场镇", "友谊路街道", "张庙街道", "高境镇", "淞南镇", "顾村镇", "罗店镇", "杨行镇", "月浦镇", "罗泾镇", "庙行镇"];

function chooseRegion(regionCode: string) {
  selectRegion(regionCode);
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
        <p class="region-current"><MapPin />上海市 · 宝山区 · {{ activeRegion.street_or_town }}</p>
        <div class="region-map-panel" aria-label="宝山区试点地区示意图">
          <svg viewBox="0 0 360 190" role="img" aria-label="大场、顾村、庙行相邻关系示意图">
            <path class="region-map-panel__district" d="M25 20h310v150H25z" />
            <path class="region-map-panel__river" d="M36 72c66-30 110 34 174 4s91-17 117 8" />
            <g v-for="(region,index) in supportedRegions" :key="region.region_code" :transform="`translate(${[92,205,270][index]} ${[118,55,125][index]})`" @click="chooseRegion(region.region_code)">
              <circle :class="{ active: activeRegion.region_code === region.region_code }" r="28" />
              <text text-anchor="middle" dy="5">{{ region.street_or_town.replace('镇','') }}</text>
            </g>
          </svg>
          <p>三个试点地区已开放；示意图仅表达相邻关系，不采集精确位置。</p>
        </div>
        <div class="region-level"><h3>上海市各区</h3><div class="region-options region-options--districts"><button v-for="district in districts" :key="district" type="button" :class="{ active: district === '宝山区' }" :disabled="district !== '宝山区'" :title="district === '宝山区' ? '当前开放区域' : '暂未开通'">{{ district }}<small v-if="district !== '宝山区'">即将开通</small><Check v-else /></button></div></div>
        <div class="region-level"><h3>宝山区街镇</h3><div class="region-options"><button v-for="town in towns" :key="town" type="button" :class="{ active: town === activeRegion.street_or_town }" :disabled="!supportedRegions.some(region => region.street_or_town === town)" :title="supportedRegions.some(region => region.street_or_town === town) ? `进入${town}` : '暂未开通'" @click="supportedRegions.find(region => region.street_or_town === town) && chooseRegion(supportedRegions.find(region => region.street_or_town === town)!.region_code)">{{ town }}<small v-if="!supportedRegions.some(region => region.street_or_town === town)">即将开通</small><Check v-else /></button></div></div>
        <p class="region-note">大场、顾村、庙行已开放。切换后首页、服务、活动、邻里、搜索和助手均按所选地区重新加载。</p>
      </section>
    </div>
  </Teleport>
</template>
