# repo-aware-reviewer

[English](README.md)

一个以证据为依据的代码审查 CLI 原型。流程参考 Anthropic Code Review，先发现候选问题，再单独对照证据复核、去重和分级。它也支持逐条验收需求，并参考人工维护的业务知识库。

## 当前能力

- 支持单 commit、分支和显式 revision range 三种范围。
- 单 commit 缺省使用第一父提交作为基线。
- 分支使用目标分支与基线分支的 merge-base。
- 固定并报告准确的 base/target commit SHA。
- 从基线 commit 读取写入证据包的仓库审查规则。
- 读取外部需求文件和人工知识库，并记录 SHA-256。
- 按变更文件分配扩展 diff 的预算，并记录截断和跳过的证据。
- 在发现和复核之间按需补充固定 Git 版本中的代码和本地 Maven 依赖证据。
- 分两次调用模型，先发现候选问题，再复核并生成报告。
- 从 Git 和本地缓存的依赖制品收集证据，无需构建或运行被审查项目。
- `--prepare-only` 可只生成证据包和两阶段提示词，不调用 Agent。

## 构建

使用 JDK 21 或以上版本，在项目目录运行以下命令。脚本直接使用 JDK 自带的工具。

```bash
bash scripts/build.sh
```

生成 `build/code-review-agent.jar`。

## 配置模型后端

两个命令默认调用 `claude -p`。启动 JAR 的环境需要能从 `PATH` 找到 `claude` 可执行文件。可以在同一个终端确认它能从 stdin 接收提示词并返回回复。

```bash
printf '%s\n' '只回复 OK' | claude -p
```

如果返回 `OK`，项目就能复用 Claude Code 已配置的认证和模型路由，包括 Claude 订阅、Anthropic API Key，或 MiniMax 等兼容网关。直连 Anthropic API 时，可通过环境变量向 Claude Code 提供 `ANTHROPIC_API_KEY`。如需换用其他可执行文件，用 `--agent-command` 指定命令，每个参数各传一次 `--agent-arg`。该命令需要从 stdin 读取提示词，并将纯文本回复写到 stdout。

CLI 在被审查仓库的工作目录启动外部 Agent。工具权限取决于外部 Agent 的配置，包含所收集证据的提示词会发给它配置的模型后端。只想在本地准备证据时，使用 `--prepare-only`。

## 生成候选知识库

从固定 Git 版本生成供人工核对的候选稿。命令会枚举所有跟踪文件，并将受支持的源码、文档、测试、Schema 和配置文件分块。

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --language zh-CN \
  --output-dir ./knowledge-output
```

加上 `--prepare-only` 可在本地生成审查范围、文件清单、证据分块和提示词。

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --prepare-only
```

先人工核对 `candidate-knowledge.md`，再通过 `--knowledge` 把确认后的主知识库传给 `review`。使用 `--language en` 或 `--language zh-CN` 选择分块观察和候选稿的语言，默认是英文。

## 使用

审查单个 commit 时可选传入业务资料。未指定目标时，默认审查 `HEAD^..HEAD`。

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --requirement /path/outside-repo/requirement.md \
  --knowledge /path/outside-repo/knowledge.md
```

两个文档参数都是可选的。实际审查材料放在仓库外，运行时显式传入路径。`--knowledge` 接收一份人工维护的 UTF-8 Markdown，章节和排版可以自由组织。`--requirement` 接收单独的 UTF-8 文件，写明本次变更的要求。未提供需求文件时，仍可审查代码问题。

从分支与基线分支的共同祖先开始审查。

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch feature/example \
  --base main
```

直接指定起止版本。

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --range origin/main..feature/example
```

只准备证据，无需调用模型。

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --prepare-only \
  --output-dir ./review-output
```

发现阶段可以请求基线或目标版本中已跟踪的文本，以及 `~/.m2/repository` 里单个 Java 类型或 `.proto` 条目。Maven 请求必须匹配所选版本根 `pom.xml` 中有明确版本的直接 JAR 依赖。CLI 记录制品哈希，用 `javap` 读取公开 Java 签名，补充证据会进入发给所配置后端的复核提示词。此过程无需构建或下载依赖。

## 输出

两个命令都把进度写到 stderr，长时间等待时会提示已用时间。成功后，stdout 会输出本次运行目录的路径。默认目录是 `review-output/` 和 `knowledge-output/`，也可以用 `--output-dir` 指定。

- `review` 保存 `scope.md`、`evidence.md`、`coverage.md` 和 `discover-prompt.md`。完整审查还会生成 `candidates.md`、`supplemental-evidence.md`、`verify-prompt.md` 和 `report.md`。`--prepare-only` 会生成 `verify-prompt-template.md` 作为复核提示词模板。
- `knowledge generate` 保存 `scope.md`、`inventory.md`、`chunks/` 下的证据和 `prompts/` 下的提示词。完整运行还会生成分块观察和 `candidate-knowledge.md`。使用 `--prepare-only` 时，汇总阶段会保存提示词模板。

## 项目文档

根目录只提交文件名以 `README` 开头的 Markdown，大小写不限。其他项目文档放在 `docs/` 等子目录。

## 当前原型边界

- 依赖读取当前覆盖本地已缓存的 Maven 直接 JAR 依赖，尚未解析父 POM/BOM、profile、传递依赖或其他包管理器，也尚未接入下载。
- 尚未实现持久化仓库注册、知识库版本审批和 Skill/MCP 包装。
- Agent 输出当前为 Markdown；后续应增加结构化 findings schema。
