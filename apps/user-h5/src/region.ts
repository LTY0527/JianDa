import { ref } from "vue";

export interface RegionSelection {
  province: string;
  city: string;
  district: string;
  street_or_town: string;
  region_code: string;
}

export const dachangRegion: RegionSelection = {
  province: "上海市",
  city: "上海市",
  district: "宝山区",
  street_or_town: "大场镇",
  region_code: "310113102",
};

export const gucunRegion: RegionSelection = {
  province: "上海市", city: "上海市", district: "宝山区",
  street_or_town: "顾村镇", region_code: "310113109",
};

export const miaohangRegion: RegionSelection = {
  province: "上海市", city: "上海市", district: "宝山区",
  street_or_town: "庙行镇", region_code: "310113112",
};

export const supportedRegions = [dachangRegion, gucunRegion, miaohangRegion] as const;

const storageKey = "jianda_region";
export const activeRegion = ref<RegionSelection>(readRegion());

function readRegion(): RegionSelection {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || "null");
    const supported = supportedRegions.find((region) => region.region_code === value?.region_code);
    if (supported) return supported;
  } catch {
    // Invalid local data falls back to the supported acceptance region.
  }
  return dachangRegion;
}

export function selectRegion(regionCode: string): void {
  const region = supportedRegions.find((item) => item.region_code === regionCode);
  if (!region) return;
  activeRegion.value = region;
  localStorage.setItem(storageKey, JSON.stringify(region));
}

export const selectDachangRegion = () => selectRegion(dachangRegion.region_code);
