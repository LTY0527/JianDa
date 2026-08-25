# Phase 9.9.3 高德真实地图真实验收报告

> 验收日期：2026-08-25
> 测试文件：`tests/e2e/real/phase9-9-3-amap.spec.ts`
> 截图：`artifacts/phase9-9-3-final/h5-amap-baoshan-390.png`、`h5-amap-gucun-selected-390.png`

## 1. 验收范围

本次为 Final Regression，不重新实现地图。仅确认 Codex 已完成的高德真实地图链路在 TRAE 接管后仍 PASS。

## 2. 真实环境

- 视口：390 × 844（移动端真实尺寸）
- 浏览器：Playwright Chromium（headless）
- H5 入口：`http://127.0.0.1/`
- 高德 SDK：真实 `_AMapService` 代理 + 安全密钥运行时注入（不编译进 bundle）

## 3. 验收点与结果

| 验收点 | 期望 | 实测 | 状态 |
|---|---|---|---|
| 高德 SDK HTTP | 所有 amap.com / _AMapService 响应 < 400 | 全部 < 400 | PASS |
| 宝山区行政边界 | `.amap-region-map__canvas[data-boundary-ready="true"]` 可见 | 可见 | PASS |
| Marker 数量 | `data-marker-count="3"` | 3 | PASS |
| 大场镇 Marker | label "大场镇" 可见 | 可见 | PASS |
| 顾村镇 Marker | label "顾村镇" 可见 | 可见 | PASS |
| 庙行镇 Marker | label "庙行镇" 可见 | 可见 | PASS |
| 地区切换 | 点击顾村 Marker → "选择所在地区" 按钮含"顾村镇" | 含"顾村镇" | PASS |
| 横向溢出 | scrollWidth ≤ clientWidth | 是 | PASS |

## 4. 行政区代码

- 宝山区：`310113`
- 大场镇、顾村镇、庙行镇：真实坐标 POI Marker

## 5. 截图证据

- `h5-amap-baoshan-390.png`：宝山区边界 + 3 Marker
- `h5-amap-gucun-selected-390.png`：点击顾村镇后地区切换

## 6. Final Gate

```
AMAP_REAL_ACCEPTANCE = PASS
AMAP_REGION_SWITCH_ACCEPTANCE = PASS
```

无回归，无需重新申请高德 Key 或重写地图。
