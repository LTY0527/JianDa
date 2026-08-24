# Phase 9.9.2 性能基线

20 次本机 Docker HTTP 采样的已知基线：

| 接口 | p50 | max |
| --- | ---: | ---: |
| 大场公开列表 | 41 ms | 205 ms |
| 顾村公开列表 | 33 ms | 43 ms |
| 庙行公开列表 | 31 ms | 32 ms |
| 机构端 health | 14 ms | — |
| Backend health | 16 ms | — |
| AI health | 17 ms | — |

长任务此前存在浏览器等待批量导入超时、Processing 每两秒并行请求多接口的问题，不能用增加前端 timeout 作为优化。
