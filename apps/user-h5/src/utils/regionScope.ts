import type { PublicItem } from "../api";

const townScopes = new Set(["LOCAL_TOWN", "TOWN", "STREET", "LOCAL"]);

export function isTownSpecific(item: PublicItem, regionCode?: string): boolean {
  return townScopes.has(item.local_scope || "")
    && Boolean(item.region_code)
    && (!regionCode || item.region_code === regionCode);
}

export function regionScopeLabel(item: PublicItem, regionCode?: string): string {
  if (isTownSpecific(item, regionCode)) return item.street_or_town || "本镇";
  if (item.local_scope === "DISTRICT_SHARED") return "宝山区";
  if (["CITY_SHARED", "CITY"].includes(item.local_scope || "")) return "上海市级";
  if (["NATIONAL_SHARED", "NATIONAL"].includes(item.local_scope || "")) return "全国";
  return item.district || item.city || "权威资讯";
}

export function regionPriority(item: PublicItem, regionCode: string): number {
  if (isTownSpecific(item, regionCode)) return 1000;
  if (item.local_scope === "DISTRICT_SHARED") return 300;
  if (["CITY_SHARED", "CITY"].includes(item.local_scope || "")) return 200;
  if (["NATIONAL_SHARED", "NATIONAL"].includes(item.local_scope || "")) return 100;
  return 0;
}
