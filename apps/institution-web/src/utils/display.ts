const coverTypes: Record<string, string> = {
  ORIGINAL_COVER: "原网页封面图",
  ARTICLE_IMAGE: "原网页正文配图",
  CATEGORY_DEFAULT: "简达分类默认图",
  AI_ILLUSTRATION: "AI 生成示意图",
  EDITOR_UPLOAD: "机构编辑上传图",
};

const authorityLevels: Record<string, string> = {
  A: "政府部门官方网站",
  B: "中央重点新闻媒体",
  C: "经人工确认的机构网站",
};

const contentKinds: Record<string, string> = {
  SERVICE_NOTICE: "办事通知",
  HEALTH_EDUCATION: "健康科普",
  POLICY_NEWS: "政策资讯",
  ANTI_FRAUD: "防诈提醒",
  COMMUNITY_SERVICE: "社区服务",
  GENERAL_NEWS: "综合资讯",
};

const statuses: Record<string, string> = {
  UPLOADED: "待处理",
  PENDING: "等待处理",
  QUEUED: "等待采集",
  PROCESSING: "处理中",
  RUNNING: "正在采集",
  SUCCESS: "采集成功",
  PARTIAL_SUCCESS: "部分成功",
  CANCELLED: "已取消",
  NEVER_RUN: "尚未运行",
  WAITING_REVIEW: "等待人工审核",
  WAITING_APPROVAL: "等待人工批准",
  WAITING_BUDGET: "等待预算恢复",
  REVIEWED: "已审核",
  PUBLISHED: "已发布",
  SUCCEEDED: "处理成功",
  UNCHANGED: "内容未变化",
  FAILED: "处理失败",
  STOPPED: "已停止",
  WITHDRAWN: "已撤回",
  ACTIVE: "启用",
  DISABLED: "停用",
  DUPLICATE: "重复内容",
};

export function coverTypeLabel(value?: string | null): string {
  return value ? coverTypes[value] || "其他封面" : "待确认";
}

export function authorityLevelLabel(value?: string | null): string {
  return value ? authorityLevels[value] || "待人工确认的来源" : "待确认";
}

export function contentKindLabel(value?: string | null): string {
  return value ? contentKinds[value] || "其他公共信息" : "待确认";
}

export function statusLabel(value?: string | null): string {
  return value ? statuses[value] || "状态待确认" : "状态待确认";
}

function validDate(value?: string | null): Date | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDisplayDate(value?: string | null): string {
  const date = validDate(value);
  if (!date) return "待人工确认";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
  }).format(date);
}

export function formatDisplayDateTime(value?: string | null): string {
  const date = validDate(value);
  if (!date) return "待人工确认";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}
