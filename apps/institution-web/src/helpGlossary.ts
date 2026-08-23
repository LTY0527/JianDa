export const helpGlossary = {
  sourceHealth: "根据最近一次采集任务、来源开关和错误记录汇总。需要关注时不会自动发布内容。",
  automaticUpdate: "按来源设定的时间检查公开页面。默认仍需人工审核，不等于自动发布。",
  discovery: "只发现可能的新文章地址，不创建材料，也不会调用 AI。",
  shadowCollection: "读取正文与图片候选供预览，不创建材料、不调用 AI、不发布。",
  aiQueue: "材料经人工批准后才会进入 AI 处理；预算不足或失败时会保留等待原因。",
  tokenBudget: "限制每天自动处理可消耗的模型文本额度，避免不可控调用。",
} as const;

export type HelpGlossaryKey = keyof typeof helpGlossary;
