<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import type { RegionSelection } from "../region";

const props = defineProps<{ regions: readonly RegionSelection[]; activeCode: string }>();
const emit = defineEmits<{ select: [regionCode: string] }>();
const container = ref<HTMLElement | null>(null);
const state = ref<"loading" | "ready" | "missing" | "failed">("loading");
const key = String(import.meta.env.VITE_AMAP_KEY || "").trim();
const securityCode = String(import.meta.env.VITE_AMAP_SECURITY_JS_CODE || "").trim();
let map: any;

function loadSdk(): Promise<any> {
  if ((window as any).AMap) return Promise.resolve((window as any).AMap);
  return new Promise((resolve, reject) => {
    const callback = `jiandaAmapReady${Date.now()}`;
    (window as any)[callback] = () => {
      delete (window as any)[callback];
      resolve((window as any).AMap);
    };
    const script = document.createElement("script");
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.DistrictSearch,AMap.Geocoder&callback=${callback}`;
    script.async = true;
    script.onerror = () => reject(new Error("AMap load failed"));
    document.head.appendChild(script);
  });
}

function district(AMap: any, keyword: string, level: string): Promise<any> {
  return new Promise((resolve, reject) => {
    new AMap.DistrictSearch({ level, subdistrict: 0, extensions: "all" })
      .search(keyword, (status: string, result: any) => status === "complete" ? resolve(result) : reject(result));
  });
}

onMounted(async () => {
  if (!key || !securityCode) {
    state.value = "missing";
    return;
  }
  try {
    (window as any)._AMapSecurityConfig = { securityJsCode: securityCode };
    const AMap = await loadSdk();
    map = new AMap.Map(container.value, { zoom: 11, viewMode: "2D", resizeEnable: true });
    const result = await district(AMap, "宝山区", "district");
    const boundary = result?.districtList?.[0]?.boundaries || [];
    if (boundary.length) {
      const polygons = boundary.map((path: any) => new AMap.Polygon({
        path, strokeColor: "#176b63", strokeWeight: 2, fillColor: "#dcece7", fillOpacity: 0.28,
      }));
      map.add(polygons);
      map.setFitView(polygons, false, [28, 28, 28, 28]);
    }
    const geocoder = new AMap.Geocoder({ city: "上海" });
    props.regions.forEach((region) => geocoder.getLocation(`上海市宝山区${region.street_or_town}`, (status: string, value: any) => {
      const location = value?.geocodes?.[0]?.location;
      if (status !== "complete" || !location) return;
      const marker = new AMap.Marker({ position: location, title: region.street_or_town,
        label: { content: `<span class="jianda-amap-label${region.region_code === props.activeCode ? " active" : ""}">${region.street_or_town}</span>`, direction: "top" } });
      marker.on("click", () => emit("select", region.region_code));
      map.add(marker);
    }));
    state.value = "ready";
  } catch {
    state.value = "failed";
  }
});

onBeforeUnmount(() => map?.destroy());
</script>

<template>
  <div class="amap-region-map">
    <div v-show="state === 'ready'" ref="container" class="amap-region-map__canvas" aria-label="高德地图宝山区行政区与已开通街镇"></div>
    <div v-if="state === 'loading'" class="amap-region-map__state">正在加载真实地图…</div>
    <div v-else-if="state === 'missing'" class="amap-region-map__state"><b>地图服务尚未配置</b><span>仍可从下方已开通地区列表切换。管理员配置高德 JS API 凭据后将显示真实地图。</span></div>
    <div v-else-if="state === 'failed'" class="amap-region-map__state"><b>地图暂时无法加载</b><span>请使用下方地区列表，不影响内容浏览。</span></div>
  </div>
</template>

<style scoped>
.amap-region-map,.amap-region-map__canvas,.amap-region-map__state{width:100%;height:330px}.amap-region-map{overflow:hidden;border:1px solid #d8e3df;background:#eef4f2}.amap-region-map__state{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:28px;text-align:center;color:#52645f}.amap-region-map__state b{font-size:18px;color:#263b35}.amap-region-map__state span{max-width:420px;line-height:1.7}
@media(max-width:600px){.amap-region-map,.amap-region-map__canvas,.amap-region-map__state{height:280px}}
</style>
