const pools: Record<string, string[]> = {
  HEALTH_EDUCATION: Array.from({ length: 6 }, (_, index) => `/images/defaults/health-${index + 1}.svg`),
  POLICY_NEWS: Array.from({ length: 5 }, (_, index) => `/images/defaults/policy-${index + 1}.svg`),
  ANTI_FRAUD: Array.from({ length: 5 }, (_, index) => `/images/defaults/fraud-${index + 1}.svg`),
  COMMUNITY_SERVICE: Array.from({ length: 5 }, (_, index) => `/images/defaults/community-${index + 1}.svg`),
  CULTURE_LEARNING: Array.from({ length: 4 }, (_, index) => `/images/defaults/culture-${index + 1}.svg`),
  SERVICE_NOTICE: Array.from({ length: 5 }, (_, index) => `/images/defaults/service-${index + 1}.svg`),
  GENERAL_NEWS: ["/images/defaults/general.svg"],
};

const aliases: Record<string, string> = {
  健康: "HEALTH_EDUCATION",
  健康科普: "HEALTH_EDUCATION",
  养老政策: "POLICY_NEWS",
  防诈: "ANTI_FRAUD",
  防诈提醒: "ANTI_FRAUD",
  社区服务: "COMMUNITY_SERVICE",
  文化学习: "CULTURE_LEARNING",
  办事通知: "SERVICE_NOTICE",
};

export interface CoverItem {
  id?: number;
  document_id?: number;
  slug?: string;
  title?: string;
  cover_image_url?: string;
  content_kind?: string;
  category?: string;
}

function stableHash(value: string): number {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function poolFor(item: CoverItem): string[] {
  const rawKey = item.content_kind || item.category || "GENERAL_NEWS";
  const key = aliases[rawKey] || rawKey;
  return pools[key] || pools.GENERAL_NEWS;
}

export function categoryDefaultCover(item: CoverItem, alternate = 0): string {
  const pool = poolFor(item);
  const identity = String(item.document_id || item.id || item.slug || item.title || "jianda");
  return pool[(stableHash(identity) + Math.max(0, alternate)) % pool.length];
}

export function articleCover(item: CoverItem): string {
  return item.cover_image_url || categoryDefaultCover(item);
}
