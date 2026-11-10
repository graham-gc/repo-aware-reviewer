# repo-aware-reviewer

[English](README.md)

一个根据代码、需求和人工维护的业务知识库审查 Git 提交的 CLI。流程参考 Anthropic Code Review，先调用模型找出候选问题，再请模型对照证据复核、去重和分级。

## 构建

需要 JDK 21 或以上版本、Bash 和 Git。在项目目录运行以下命令，生成 `build/code-review-agent.jar`。

```bash
bash scripts/build.sh
```

## 使用本机 Claude Code

两个命令默认通过 `claude -p` 复用本机的 Claude Code 配置。确保 `claude` 可执行文件在 `PATH` 上，然后在运行 JAR 的同一个终端执行下面的检查。返回 `OK` 就说明已接通。

```bash
printf '%s\n' '只回复 OK' | claude -p
```

CLI 在被审查仓库的工作目录启动外部 Agent。它的配置决定工具权限和含证据的提示词会发往哪个模型后端。换用其他 Agent 时，用 `--agent-command` 指定可执行文件，每个参数各传一次 `--agent-arg`。该命令需要从 stdin 读取提示词，并把回复写到 stdout。

## 生成候选知识库

扫描指定 Git 版本，生成供人工核对的候选稿。命令会列出 Git 跟踪条目，并将受支持的文本文件分块。符号链接和子模块指针会记为跳过。

```bash
java -jar build/code-review-agent.jar knowledge generate --repo /path/to/repo
```

默认扫描 `HEAD` 并生成英文稿。用 `--ref` 选择其他版本，用 `--language zh-CN` 生成简体中文稿。人工核对 `candidate-knowledge.md` 后，把确认的版本传给 `review`。

## 使用

审查最新提交，可选传入业务资料。

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --requirement /path/outside-repo/requirement.md \
  --knowledge /path/outside-repo/knowledge.md
```

审查材料放在仓库外。`--knowledge` 接收一份 UTF-8 Markdown，无需固定排版。`--requirement` 接收单独的 UTF-8 需求文件，用于逐条验收。两个参数都可选。

- `--commit <ref>` 审查指定提交相对第一父提交的变更，默认目标是 `HEAD`。第一父提交须在本地可用。
- `--branch <ref> --base <ref>` 审查目标分支从双方共同祖先起的变更。
- `--range BASE..TARGET` 比较显式指定的两个版本。

证据包中的仓库审查规则来自实际解析出的基线提交。

## 输出与限制

两个命令都把进度写到 stderr，成功后把本次运行目录写到 stdout。默认输出目录是 `review-output/` 和 `knowledge-output/`，可用 `--output-dir` 更改。任一命令加上 `--prepare-only`，即可在本地保存证据和提示词，无需调用模型。

- `review` 把模型报告保存为 `report.md`。可查看 `scope.md` 核对实际提交和输入哈希，查看 `evidence.md` 核对证据，查看 `coverage.md` 了解变更截断、跳过或补证失败的情况。
- `knowledge generate` 生成 `candidate-knowledge.md`。可查看 `inventory.md` 了解哪些文件被纳入或跳过。两种流程都会在本地保存各自的证据。

复核前，采集器可按请求读取基线或目标提交中已跟踪的文本。依赖补证只读取所选版本根 `pom.xml` 中声明了固定版本且已缓存的直接 JAR 依赖，提取 Java 公开签名和 `.proto` 文件。采集过程无需运行目标项目的构建或测试。输入较大时，`review` 可用 `--max-evidence-bytes`，`knowledge generate` 可用 `--max-file-bytes` 和 `--max-total-bytes` 调整预算。

## 项目文档

根目录只提交文件名以 `README` 开头的 Markdown，大小写不限。其他项目文档放在 `docs/` 等子目录。
