# Phase 9.6 真实采集报告

更新时间：2026-08-23（Asia/Shanghai）

## 来源状态

- source registry id：5
- source name：宝山区政府信息公开·大场镇
- domain：xxgk.shbsq.gov.cn
- discovery mode：SECTION
- source state before：enabled=false
- source state after：enabled=false
- 安全门禁：同源白名单、SSRF、robots、限速和响应大小限制均沿用生产服务

## 修复前真实 discover

- duration：5305 ms
- candidate count：20
- duplicate count：0
- errors：9
- 结果质量：失败；候选主要为站点导航、分类和部门目录，不是文章

## 修复后真实 discover

- 入口：`https://xxgk.shbsq.gov.cn/infoDirectory.html?dept=003006&rn=%E5%A4%A7%E5%9C%BA%E9%95%87&type=dept`
- duration：5530 ms
- recent days：30
- candidate count：4
- duplicate count：0
- filtered navigation count：82
- filtered external count：0
- errors：0
- 最新候选：2026-08-14《兴业惠民 共筑大场——大场镇2026年政府开放日》

## 真实 shadow

- URL：`https://xxgk.shbsq.gov.cn/article.html?infoid=6513958d-e52a-41d5-90a1-c8f47c24bf1f`
- duration：3505 ms
- robots：NOT_FOUND_ALLOW
- title：兴业惠民 共筑大场——大场镇2026年政府开放日
- published at：2026-08-14
- content kind：COMMUNITY_SERVICE
- extracted text：799 字符
- 原始图片元素：7（其中 6 张正文 JPEG、1 个分享二维码；进入候选下载门禁后仍需过滤）
- collect：成功创建文档 63，进入 AI 等待审批队列；没有自动审核或发布
- AI queue：queue 19，经显式批准后执行
- External：processing job 72，provider `external`，model `deepseek-v4-flash`，prompt `web-v1.1`，schema `1.1`
- Token：prompt 2501、completion 1576、total 4077；耗时 10318 ms
- review：通过业务 API 完成事实核对和生成内容修正，segment 105 可追溯
- publish：`news-63`，地区 `310113102`，H5 `http://127.0.0.1/news/news-63`

## 真实 scheduler

- 初次 job 28：按正常 7 天窗口执行，正确发现 0 条。
- 验收 job 29：临时将单一来源窗口改为 30 天并启用来源及自动采集，scheduler identity 为 `jianda-crawl-scheduler-v1`。
- 结果：discovered 3、added 3、duplicate 0、failed 0；新材料只进入 AI 审批流，未自动 AI、审核或发布。
- finally 恢复：`enabled=false`、`allow_auto_crawl=false`、`recent_days=7`。

本报告只记录真实网络与真实 Docker 结果，不将离线 fixture 计入真实验收。
