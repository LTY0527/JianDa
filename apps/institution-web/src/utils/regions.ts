export interface TownRegion {
  code: string;
  name: string;
}

export const townRegions: readonly TownRegion[] = [
  { code: "310113102", name: "大场镇" },
  { code: "310113109", name: "顾村镇" },
  { code: "310113112", name: "庙行镇" },
] as const;

export interface RegionScopePayload {
  localScope: "LOCAL_TOWN";
  province: "上海市";
  city: "上海市";
  district: "宝山区";
  streetOrTown: string;
  regionCode: string;
}

export function townRegionScope(regionCode: string): RegionScopePayload {
  const region = townRegions.find((item) => item.code === regionCode);
  if (!region) throw new Error("请选择已开放的街镇");
  return {
    localScope: "LOCAL_TOWN",
    province: "上海市",
    city: "上海市",
    district: "宝山区",
    streetOrTown: region.name,
    regionCode: region.code,
  };
}

export function supportedTownCode(value?: string): string {
  return townRegions.some((item) => item.code === value) ? String(value) : "";
}
