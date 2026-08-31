# 邻里发布误触图片选择器 P0 验收报告

日期：2026-08-31  
环境：Docker 本地真实服务，Chromium，390×844 视口  
页面：`http://127.0.0.1/neighborhood`

## Bug 现象与根因

点击“发布”时，移动端可能错误打开系统文件选择器。

真实根因是发布区使用了“`label` 包裹隐藏 file input + 相邻发布 button + 移动端共同 flex 扩张”的高风险 DOM/CSS 组合；file input 又采用绝对定位隐藏，文件选择动作缺少唯一、明确的程序化入口，导致移动浏览器可能把相邻点击委托给 file input。

## 修复方式

- 将 file input 从 `label` 中彻底拆出，设为不占点击区域且 `pointer-events: none`。
- 新增 `mediaInput` 引用和唯一入口 `openImagePicker()`；只有“选择图片”按钮调用它。
- “发布”改为独立 `.community-publish-button`，使用 `@click.stop.prevent="publish"`。
- 发布禁用条件仅为正文为空、提交中或图片上传中；没有图片仍可发布纯文字帖。
- 移动端发布区改为两列 grid，两个按钮均不小于 48px，几何点击区域互不重叠。
- `publish()` 只负责上传已选图片、创建帖子、清空草稿/图片并刷新 feed，不调用文件选择器。

## 真实验证结果

| 验证项 | 结果 |
| --- | --- |
| 页面身份、正文渲染、无框架错误层 | PASS |
| 点击发布触发 filechooser 次数 | PASS，0 次 |
| 点击选择图片触发 filechooser 次数 | PASS，1 次 |
| 0 张图片 + 非空正文发布 | PASS，真实 POST 成功且 feed 可见 |
| 1 张图片 + 非空正文发布 | PASS，媒体上传、帖子创建和 feed 图片均可见 |
| 两个按钮 bounding box | PASS，交叠面积为 0 |
| 发布按钮中心 `elementFromPoint` | PASS，命中 `.community-publish-button` |
| 390px 页面横向溢出 | PASS，无横向滚动 |
| 浏览器 console error | PASS，0 条 |

测试使用仓库已有合法 PNG：`artifacts/phase9-8-real-acceptance/resident-post-image.png`。

## 构建与测试

- `npm run typecheck --workspace apps/user-h5`：PASS。
- `npm run build --workspace apps/user-h5`：PASS，1767 modules transformed。
- `docker compose build frontend`：PASS。
- `docker compose up -d frontend`：PASS。
- `npx playwright test tests/e2e/real/neighborhood-publish-fix.spec.ts`：PASS，1 passed（用例内覆盖纯文字、图文、filechooser 和 hitbox 五组断言）。
- Docker 四项业务服务：healthy；四个健康地址均为 HTTP 200。

## 截图

- `01_neighborhood_compose_390x844.png`：初始发布区和独立双按钮。
- `02_publish_button_independent.png`：发布按钮独立 hitbox。
- `03_text_post_published.png`：纯文字帖子真实发布成功。
- `04_image_picker_still_works.png`：选择图片后真实预览正常。

