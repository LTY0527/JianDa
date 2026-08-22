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

const storageKey = "jianda_region";
export const activeRegion = ref<RegionSelection>(readRegion());

function readRegion(): RegionSelection {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || "null");
    if (value?.region_code === dachangRegion.region_code) return value;
  } catch {
    // Invalid local data falls back to the supported acceptance region.
  }
  return dachangRegion;
}

export function selectDachangRegion(): void {
  activeRegion.value = dachangRegion;
  localStorage.setItem(storageKey, JSON.stringify(dachangRegion));
}
