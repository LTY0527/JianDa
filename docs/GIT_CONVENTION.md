# Git 协作与中文进度提交规范

本规范适用于“简达”后续所有功能、界面、测试、文档和部署变更。

## 开始开发前

```bash
git status
git branch --show-current
git log --oneline -8
```

确认当前分支、工作区改动、远端领先或落后状态和最近开发进度。已有未提交修改默认属于原作者，不得覆盖、删除或混入无关提交。

## 提交原则

- 每完成一个可独立说明、验证和回退的小任务，创建一次提交。
- 使用 `<类型>(<模块>): <中文具体进度>` 格式。
- 冒号后的说明必须指出模块、完成能力和实际阶段，不使用 `update`、`fix`、`wip`、“更新代码”、“修复问题”等模糊描述。
- 不把无关功能合并成巨大提交；优先使用明确文件列表执行 `git add`。
- 提交前至少执行相关最小测试，测试失败或核心流程损坏时不得提交。
- 不提交密钥、`.env`、密码、JWT 密钥、`node_modules`、虚拟环境、构建产物、日志、IDE 文件或用户上传内容。
- 默认只提交，不自动 push；禁止擅自强推、硬重置、清理工作区或重写远程历史。

常用类型包括 `feat`、`fix`、`refactor`、`style`、`test`、`docs`、`chore`、`perf`、`build`、`ci` 和 `revert`。模块使用实际范围，例如 `h5`、`admin`、`assistant`、`backend`、`database`、`navigation`、`docs` 或 `test`。

合格示例：

```text
feat(h5): 完成首页重要提醒与今日必看内容流
feat(assistant): 完成基于已发布内容的问答与来源引用
fix(navigation): 修复详情页直接访问时返回空白页面
test(h5): 补充收藏历史和撤回过滤端到端测试
docs(project): 更新Phase 7.2开发进度与演示流程
```

## 提交前检查

```bash
git status
git diff --stat
git diff
```

确认变更范围、敏感信息、调试代码、旧迁移和临时文件，再运行相关命令：

```bash
npm run typecheck
npm run build
npm run test:e2e
mvn test
pytest -q
```

只记录实际运行结果。提交后更新 `docs/TASKS.md`，记录提交哈希、中文说明、完成内容、测试结果和下一项任务，并复查：

```bash
git status
git log --oneline -5
```

## 推送规则

用户明确要求推送前，先执行：

```bash
git status
git log --oneline origin/main..HEAD
```

确认提交范围后再执行普通 `git push origin main`。不得自动执行 `--force`。