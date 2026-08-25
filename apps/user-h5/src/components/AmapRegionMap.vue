<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import type { RegionSelection } from "../region";

const props = defineProps<{ regions: readonly RegionSelection[]; activeCode: string }>();
const emit = defineEmits<{ select: [regionCode: string] }>();
const container = ref<HTMLElement | null>(null);
const state = ref<"loading" | "ready" | "missing" | "failed">("loading");
const key = String(import.meta.env.VITE_AMAP_KEY || "").trim();
const securityCode = String(import.meta.env.VITE_AMAP_SECURITY_JS_CODE || "").trim();
const serviceHost = String(import.meta.env.VITE_AMAP_SERVICE_HOST || "").trim();
const boundaryReady = ref(false);
const markerCount = ref(0);
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
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.DistrictSearch,AMap.PlaceSearch&callback=${callback}`;
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

function findPublicOffice(AMap: any, townName: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const keyword = `${townName}人民政府`;
    new AMap.PlaceSearch({ city: "上海市", citylimit: true, pageSize: 5 })
      .search(keyword, (status: string, value: any) => {
      const candidates = value?.poiList?.pois || [];
      const office = candidates.find((item: any) => item?.name === keyword) || candidates[0];
      const location = office?.location;
      status === "complete" && location ? resolve(location) : reject(value);
    });
  });
}

onMounted(async () => {
  if (!key || (!securityCode && !serviceHost)) {
    state.value = "missing";
    return;
  }
  try {
    (window as any)._AMapSecurityConfig = serviceHost
      ? { serviceHost }
      : { securityJsCode: securityCode };
    const AMap = await loadSdk();
    map = new AMap.Map(container.value, { zoom: 11, viewMode: "2D", resizeEnable: true });
    const result = await district(AMap, "310113", "district");
    const districtItem = (result?.districtList || []).find((item: any) => String(item?.adcode) === "310113");
    const boundary = districtItem?.boundaries || [];
    if (boundary.length) {
      const polygons = boundary.map((path: any) => new AMap.Polygon({
        path, strokeColor: "#176b63", strokeWeight: 2, fillColor: "#dcece7", fillOpacity: 0.28,
      }));
      map.add(polygons);
      map.setFitView(polygons, false, [28, 28, 28, 28]);
      boundaryReady.value = true;
    }
    const labelDirections = ["top", "right", "left"];
    const markers = await Promise.all(props.regions.map(async (region, index) => {
      // Address geocoding may silently fall back to the district centroid for
      // POI names. Search the exact public office POI instead, so every marker
      // has a verifiable town-level position without hard-coding coordinates.
      const location = await findPublicOffice(AMap, region.street_or_town);
      const marker = new AMap.Marker({ position: location, title: region.street_or_town,
        label: { content: `<span class="jianda-amap-label${region.region_code === props.activeCode ? " active" : ""}">${region.street_or_town}</span>`, direction: labelDirections[index] || "top" } });
      marker.on("click", () => emit("select", region.region_code));
      return marker;
    }));
    map.add(markers);
    markerCount.value = markers.length;
    if (!boundaryReady.value || markerCount.value !== props.regions.length) throw new Error("AMap data incomplete");
    state.value = "ready";
  } catch {
    state.value = "failed";
  }
});

onBeforeUnmount(() => map?.destroy());
</script>

<template>
  <div class="amap-region-map">
    <div
      ref="container"
      class="amap-region-map__canvas"
      :class="{ 'is-pending': state !== 'ready' }"
      aria-label="高德地图宝山区行政区与已开通街镇"
      :data-boundary-ready="boundaryReady"
      :data-marker-count="markerCount"
    ></div>
    <div v-if="state === 'loading'" class="amap-region-map__state">正在加载真实地图…</div>
    <div v-else-if="state === 'missing'" class="amap-region-map__state"><b>地图服务尚未配置</b><span>仍可从下方已开通地区列表切换。管理员配置高德 JS API 凭据后将显示真实地图。</span></div>
    <div v-else-if="state === 'failed'" class="amap-region-map__state"><b>地图暂时无法加载</b><span>请使用下方地区列表，不影响内容浏览。</span></div>
  </div>
</template>

<style scoped>
.amap-region-map,.amap-region-map__canvas,.amap-region-map__state{width:100%;height:330px}.amap-region-map{position:relative;overflow:hidden;border:1px solid #d8e3df;background:#eef4f2}.amap-region-map__canvas.is-pending{visibility:hidden}.amap-region-map__state{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:28px;text-align:center;color:#52645f;background:#eef4f2}.amap-region-map__state b{font-size:18px;color:#263b35}.amap-region-map__state span{max-width:420px;line-height:1.7}
.amap-region-map__canvas :deep(.amap-marker-label),.amap-region-map__canvas :deep(.jianda-amap-label){pointer-events:none}
@media(max-width:600px){.amap-region-map,.amap-region-map__canvas,.amap-region-map__state{height:280px}}
</style>
