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
- collect：待执行
- scheduler：待执行

本报告只记录真实网络与真实 Docker 结果，不将离线 fixture 计入真实验收。
