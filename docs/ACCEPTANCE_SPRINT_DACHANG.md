# 大场镇真实社区三天验收冲刺记录

## 启动基线

- 启动日期：2026-08-23
- 工作分支：`feat/phase9-4-acceptance-sprint-v1`
- 启动提交：`a9f7223 test(P0): 增加导入预算补图和诊断回归`
- 工作区基线：仅 `artifacts/phase7-3/` 下 15 张历史验收截图存在用户修改；本轮不移动、不覆盖、不重新生成、不暂存这些文件。
- 本机 `.env` 已被 Git 忽略且未纳入版本控制；本记录不包含密钥、令牌或完整环境变量值。

## 启动时运行现场

- Docker 的 MySQL、AI、后端和前端四项服务在上一轮验收中均为 healthy，四个健康接口均返回 HTTP 200。
- 当前安全配置检查显示 External Provider 已启用，模型标识为 `deepseek-v4-flash`，凭证已配置；本文不记录凭证内容。
- 真实网页单次处理已成功：`document_id=57`、`processing_job_id=66`，Provider 为 External，模型为 `deepseek-v4-flash`，总 Token 2763，总耗时 10113 ms。
- 真实短 PDF 已跨过模型调用边界，但在 `accessible_rewrite` 阶段失败：`document_id=58`、`processing_job_id=67`，最终状态 FAILED、进度 35%，总 Token 2725。失败终态没有完整保留 Provider/模型诊断字段，需先离线定位并修复，再进行有限次数真实复测。
- 两个公开网页预览曾返回 502，且未创建材料、未调用模型；应归类为第三方页面或 Collector 失败，不归类为 AI 失败。

## P0：验收阻断项

- [x] 文本型 PDF 使用 PyMuPDF 提取真实分页正文并保留可追溯 segment。
- [x] 无文本的扫描 PDF 页面使用容器内 Tesseract 中文 OCR 回退，不增加在线 OCR 或第五个 Docker 服务。
- [x] OCR 不可用或识别失败时返回明确、可操作且不泄露内部信息的错误。
- [x] 补齐文本 PDF、扫描 PDF、多页混合 PDF 的自动测试。
- [ ] 处理任务展示读取文件、提取文本、OCR、调用 DeepSeek、校验溯源、保存结果和完成等真实阶段。
- [ ] 页面刷新后仍可按 job ID 恢复进度；适老化改写重试不重复执行已经成功的事实提取。
- [x] 修复 External 失败终态的 Provider、模型、请求编号、Token、耗时和安全错误诊断保存。
- [ ] 离线回归通过后，仅执行必要次数的真实 DeepSeek PDF 验收并记录证据。

## P1：大场镇产品验收

- [x] 通过 V21 配置默认停用的大场镇政府信息公开来源，限制每次最多 5 篇；启用与真实采集仍待人工验收。
- [ ] 采集始终人工审核、不得自动发布；图片默认不缓存，未审核图片不公开。
- [x] 新增省、市、区、街道或镇、社区、区域编码和本地范围字段，使用 V21 Flyway migration。
- [x] 实现首个支持区域“上海市 → 宝山区 → 大场镇”的选择状态与大场镇本地化 H5 首页。
- [ ] 优化 H5 首页、详情页、24px 大字模式和 375×812、390×844、1440×900 布局。
- [x] 将底部“听一听”调整为“邻里”；邻里 MVP 仅展示真实已发布本地内容，空数据明确提示。

## 本轮自动验证

- AI 全量：97 passed。
- External/OCR 定向：63 passed；新增 OCR 与诊断定向 4 passed。
- Maven P0 诊断单项：1 passed。
- 采集日期稳定性回归：7 passed；原固定日期超出 recent-days 窗口的问题已修复。
- V21 migration 与公开内容排序：3 passed。
- 用户端 typecheck 通过，生产构建通过。
- [ ] 完成最小居民账号与个人页闭环。

## P2：可延后项

- [ ] 扩展更多社区、复杂互动、个性化推荐和非验收必要的运营能力。
- [ ] 在 P0、P1 稳定前不扩大到新的基础设施或自动发布能力。

## 验收与提交规则

- 每个独立阶段运行对应 AI、Maven、前端和 Playwright 测试，并使用中文小提交。
- 真实 External 调用必须限次；不输出 API Key、Authorization 或 Bearer 内容。
- 不删除数据库、上传文件或 Docker volumes，不执行 `docker compose down -v`。
- 未审核内容和第三方图片不得自动公开，不执行 `git push`。
- 前端可视化验收说明：Browser plugin not available; used repository Playwright.
