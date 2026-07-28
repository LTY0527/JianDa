const defaults: Record<string, string> = {
  HEALTH_EDUCATION: "/images/defaults/health.svg",
  POLICY_NEWS: "/images/defaults/policy.svg",
  ANTI_FRAUD: "/images/defaults/fraud.svg",
  COMMUNITY_SERVICE: "/images/defaults/community.svg",
  SERVICE_NOTICE: "/images/defaults/service.svg",
  GENERAL_NEWS: "/images/defaults/general.svg",
  健康: "/images/defaults/health.svg",
  养老政策: "/images/defaults/policy.svg",
  防诈: "/images/defaults/fraud.svg",
  社区服务: "/images/defaults/community.svg",
  文化学习: "/images/defaults/culture.svg",
  办事通知: "/images/defaults/service.svg",
};

export function categoryDefaultCover(item: {
  content_kind?: string;
  category?: string;
}): string {
  return defaults[item.content_kind || ""] || defaults[item.category || ""] || defaults.GENERAL_NEWS;
}

export function articleCover(item: {
  cover_image_url?: string;
  content_kind?: string;
  category?: string;
}): string {
  return item.cover_image_url || categoryDefaultCover(item);
}
