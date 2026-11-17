# repo-aware-reviewer

[English](README.md)

通过本机 Claude Code 审查 Git 提交，可结合需求文档和人工确认的业务知识库，生成有代码证据的静态审查报告。

## 首次使用

### 1. 准备并构建

需要 JDK 21 或以上版本、Git、Bash，以及已经配置好、能正常运行的 `claude` 命令。目标项目须在本地，包含要审查的提交及其父提交。

```bash
git clone https://github.com/graham-gc/repo-aware-reviewer.git
cd repo-aware-reviewer
bash scripts/build.sh
printf '%s\n' '只回复 OK' | claude -p
```

返回 `OK` 后即可使用。本工具复用本机 Claude Code 的认证和模型路由，无需另行配置 API Key。已有本工具代码时，直接进入其目录构建即可。

下方命令均在本工具项目目录执行。`/path/to/project` 是被审查项目；请替换示例路径和 `abc1234`。从其他目录调用时，将 JAR 路径改为绝对路径。

### 2. 完成第一次代码审查

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --language zh-CN
```

这条最小命令会审查 **`HEAD` 相对第一父提交的改动**，生成中文报告。它不审查未提交改动，不自动拉取远端代码，也不自动生成或加载主知识库。没有传入需求文档时，不做针对外部需求的逐条验收。

所有审查均为静态阅读，不执行目标项目的构建或测试。报告默认语言是英语；`--language zh-CN` 指定中文，`--language en` 指定英语。该参数选择报告语言，不是编程语言。

运行结束后，终端输出本次目录。打开其中的 `report.md`，并检查 `review-status.md`。第一次运行即可做代码审查；需要业务审查但还没有主知识库时，先使用下面的场景 1。

## 常见使用场景

### 1. 新项目：生成候选业务知识库

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/project \
  --ref HEAD \
  --language zh-CN
```

扫描本地 `HEAD` 中的已跟踪文件，对支持的文本分块分析，生成 `candidate-knowledge.md`。默认输出到 `knowledge-output/` 下的本次目录；省略 `--ref` 也默认扫描 `HEAD`。

人工核对、修正候选稿后，另存为主知识库，再通过 `--knowledge` 传入。候选稿不会自动成为主知识库；已有人工知识库时可跳过这一步。

### 2. 指定提交：结合需求和主知识库审查

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --commit abc1234 \
  --requirement /path/to/review-docs/requirement.md \
  --knowledge /path/to/review-docs/knowledge.md \
  --knowledge-mode scoped \
  --language zh-CN
```

比较 `abc1234` 与其第一父提交，并针对需求逐项评估。`--knowledge` 接收一份人工确认的 UTF-8 Markdown，`--requirement` 接收独立的 UTF-8 需求文件；两者都使用显式路径，互不依赖，也不要求放在被审查仓库内。

只审代码时，去掉 `--requirement`、`--knowledge` 和 `--knowledge-mode`。只用需求或只用知识库也可以；`--knowledge-mode scoped` 必须配合 `--knowledge`，表示按需读取相关章节，省略此参数则默认传入整份知识库。省略 `--commit` 则审查 `HEAD`。

### 3. 比较两个本地版本

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --branch HEAD \
  --base main \
  --language zh-CN
```

直接比较本地 `main` 与 `HEAD` 的快照，等同于 `git diff main HEAD`。可以将 `HEAD` 换成开发分支名或提交 ID，将 `main` 换成其他本地可用基线。它不会自动更新本地 `main`。

### 4. 与远端最新基线比较

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --branch HEAD \
  --base main \
  --base-remote origin \
  --language zh-CN
```

先从已配置的 `origin` 获取远端 `main`，再直接与本地 `HEAD` 比较。目标版本必须已在本地；获取使用仓库已有的 Git 认证，不需要给本工具单独传 Git Token。

基线也可改为 `--base release/1.0`。启用 `--base-remote` 时写远端分支名 `main`，不写 `origin/main`。`--branch` 与 `--base` 必须成对使用，不能与 `--commit` 混用。需要业务校验时，可追加场景 2 的需求和知识库参数。

### 5. 续跑中断或未完成的审查

下例对应首次使用的最小命令：

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --language zh-CN \
  --resume /path/to/previous-run \
  --review-parallelism 1
```

将 `/path/to/previous-run` 替换为此前终端输出的运行目录。原命令如果指定了提交、分支、基线、需求或知识库，续跑时须保留相同参数和值。目标提交、文档内容和证据设置也须与原运行一致；原来使用 `HEAD` 时，`HEAD` 必须仍指向原提交。程序重新校验并复用有效任务，继续无效、缺失或提示词已变化的任务。

并发可以调整；`--review-parallelism 1` 让独立审查者依次执行，默认值仍是 `3`。远端基线在续跑时复用原快照，不重新获取；要比较更新后的远端基线，请开启新审查。

### 6. 先检查范围和证据，不调用模型

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --commit abc1234 \
  --language zh-CN \
  --prepare-only
```

只保存审查范围、证据和提示词，不生成最终审查结论。`knowledge generate` 也支持 `--prepare-only`。

## 结果与常用设置

输出目录默认位于**执行命令时的当前工作目录**下：`review-output/` 保存审查，`knowledge-output/` 保存候选知识库。追加 `--output-dir /path/to/output` 可以更改。

- `report.md`：审查报告；`review-status.md`：本轮是否审完。
- `scope.md`：实际比较的提交；`coverage.md`：证据缺口和复核情况。
- `timings.md`：本次调用次数、输入大小、阶段实际耗时和复用情况。
- `candidate-knowledge.md`：供人工核对的候选知识库，不是已批准规则。

| 退出码 | 含义 |
| --- | --- |
| `0` | 输出已完成、仅准备完成，或空 diff 已跳过；不代表代码验收通过。 |
| `3` | 审查未完成，查看报告后用 `--resume` 补齐。 |
| `1` | 执行失败，查看终端错误及调用日志。 |
| `2` | 命令参数错误。 |

模型调用默认超时为 900 秒，可用 `--agent-timeout-seconds` 调整。空响应和明确的临时错误在可验证进程清理后，默认最多额外重试一次；用 `--agent-retries 0` 关闭。检查未完成与业务证据不足会分别标明；审查完成不代表需求全部满足或测试实际运行通过。

候选复核默认按相关完整证据块提供上下文。需要做输入量和质量对照时，可加 `--verification-evidence full` 恢复全量证据；这不会减少独立审查或放宽问题确认标准。

## 进一步阅读

- [GitLab CI/CD：MR 自动审查与 `/review` 评论触发](docs/gitlab-integration.zh-CN.md)
- [候选复核性能与对照方法](docs/review-performance.md)
- [运行失败、重试、预算与续跑](docs/runtime-reliability.md)
- [语言支持范围与依赖读取](docs/language-modules.md)
- [GitHub Actions 接入示例](examples/github-review.yml)
