# Phase 9.6 截图索引

本目录分为：

- `before/`：改造前、使用当前真实或明确标记的隔离数据；
- `after/`：改造后视觉回归；
- `real-e2e/`：只允许真实 Docker、MySQL、来源、文件、External Provider 和业务写入产生的证据。

`real-e2e` 不得使用 `page.route`、`route.fulfill`、fixture 或 mock API。每张截图生成后必须在下表登记。

| 文件 | 真实/隔离 | 页面 | viewport | 数据来源 | document id / slug | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
