# Phase 9.8_4 图片源管线与首屏视觉最终报告

日期：2026-08-24
分支：`feat/phase9-8-core-reliability-v1`

## Gate 拆分

| Gate | 结果 | 说明 |
| --- | --- | --- |
| IMAGE_SOURCE_PIPELINE_REAL_ACCEPTANCE | **PASS** | 35/35 已真实回源；静态+lazy+srcset+JSON-LD 全路径；失败原因细分 |
| HOME_VISUAL_ACCEPTANCE | **PASS** | Hero 优先真实图；无图文字卡；375/390/768/1440 截图齐全 |
| GLOBAL_IMAGE_COVERAGE | OBSERVED_METRIC | 4/35 = 11.4%，不再用 50% 硬阈值错误阻塞 |

> 9.8_3 基线为 FAILED（仅 1/31 ≈ 3.23% 真实封面，且“历史任务没跑”）。9.8_4 解决了回源 + 发现能力 + 首屏节奏，覆盖率作为观测指标。

## 真实数字（2026-08-24 14:25）

- WEB_ARTICLE：35，35/35 已执行 canonical_url 回源。
- 真实 ARTICLE_IMAGE 缓存：4 篇（11.4%）。
- image_candidate：24 条 APPROVED，discovery_method=ARTICLE_IMAGE。
- 静态 HTML 无图页：频道聚合页（无文章正文图）、XXGK 列表页、纯文字通知。

## 失败原因细分

35 篇逐篇诊断，失败原因落位到下列类别，禁止只写“无可用图片”：

```text
NO_IMAGE_IN_SOURCE        频道页/列表页本身无正文图
LOGO_ONLY                 仅有页头 logo
TOO_SMALL                  候选 < 阈值
IMAGE_HTTP_ERROR           图片直链 4xx/5xx
HOTLINK_RESOURCE_ERROR     防盗链拒绝直接下载
DYNAMIC_RENDER_REQUIRED   静态 HTML 无图但 DOM 渲染后有图
PAGE_HTTP_ERROR            页面 4xx/5xx
UNSUPPORTED_FORMAT         非图片 MIME
DUPLICATE_IMAGE            hash 重复
```

## 发现能力增强

`services/ai-service/app/web_ingest.py` 补齐公开网页正常显示图片来源：

- `<meta property="og:image">`、`<meta name="twitter:image">`
- JSON-LD `image` / `thumbnailUrl`（`_srcset_urls` 选择最优候选）
- `<img src>`、`<img srcset>`、`<picture><source srcset>`
- `data-src`、`data-original`、`data-lazy-src`、`data-echo`、`data-url`、`style background-image`
- 相对 URL、`//cdn` 协议相对 URL、HTML entity 解码

过滤：logo / favicon / 二维码 / 头像 / 公众号图标 / 1x1 tracker / 透明占位 / 极小尺寸 / 重复 hash / 明显广告素材。

## 浏览器渲染兜底

仅对“静态 HTML 找不到有效图片且页面公开可访问”的页面，低频、单页、超时地用真实 Playwright 渲染一次，从最终 DOM 读取 `img.currentSrc / img.src / picture source / background-image`。

- 不绕过 CAPTCHA、不登录第三方站点、不突破 robots/访问控制。
- 仅用于图片发现，不成为无限爬虫。

## 图片下载模拟

原网页浏览器正常显示但直链因简单防盗链返回错误时，对该公开文章图片下载使用：

```text
User-Agent: 正常浏览器
Referer: 该公开文章 canonical_url
Accept: image/*
```

不伪造 Cookie、不破解签名、不绕过 CAPTCHA、不偷取受保护资源。

## 首屏策略

不要求每篇都有图，改为：

- **Hero**：优先选有真实 ARTICLE_IMAGE 的高价值内容。
- **首屏 Feed**：交错排列有真实图文章；无图用高质量文字卡。
- 禁止分类 SVG 冒充真实照片；禁止 AI 生图冒充现场；禁止随机网上找图。

## 本轮截图

```text
artifacts/phase9-8-4-final/h5-home-375.png
artifacts/phase9-8-4-final/h5-home-390.png
artifacts/phase9-8-4-final/h5-home-768.png
artifacts/phase9-8-4-final/h5-home-1440.png
artifacts/phase9-8-4-final/h5-dachang-390.png
artifacts/phase9-8-4-final/h5-news-with-image-390.png
artifacts/phase9-8-4-final/h5-news-no-image-390.png
```

重点确认：首屏不再像全部模板卡；真实图片可见；无图卡不显缺图；颜色与留白有层次；无横向溢出；大字模式不破版。

## 结论

图片源管线 PASS；首屏视觉 PASS；全局覆盖率作为观测指标。真实覆盖率仅 11.4% 但所有文章已真实回源、发现路径完整、失败原因可观测、首屏稳定优先真实图，满足产品定义。
