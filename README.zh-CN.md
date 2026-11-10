# repo-aware-reviewer

[English](README.md)

一个只读、证据驱动的代码审查 CLI 原型。流程参考 Anthropic Code Review 的核心方法：先发现候选问题，再用独立上下文复核、去重并按严重度输出；在此基础上增加需求逐条验收和人工知识库对照。

## 当前能力

- 支持单 commit、分支和显式 revision range 三种范围。
- 单 commit 缺省使用第一父提交作为基线。
- 分支使用目标分支与基线分支的 merge-base。
- 固定并报告准确的 base/target commit SHA。
- 从 base commit 读取仓库审查规则，避免采用目标改动篡改的规则。
- 读取外部需求文件和人工知识库，并记录 SHA-256。
- 按变更文件分配扩展 diff 的预算，并记录截断和跳过的证据。
- 在发现和复核之间按需补充固定 Git 版本中的代码和本地 Maven 依赖证据。
- 两阶段 Agent 调用：发现候选问题，再独立复核并生成最终报告。
- 默认不构建、不测试、不运行被审查仓库代码，也不修改被审查仓库。
- `--prepare-only` 可只生成证据包和两阶段提示词，不调用 Agent。

## 构建

需要 JDK 21 或以上，不需要 Maven 或 Gradle：

```bash
bash scripts/build.sh
```

生成 `build/code-review-agent.jar`。

## 生成候选知识库

仓库接入从固定 Git 版本的只读扫描开始。该命令会枚举全部 tracked files，将支持的源码、文档、测试、Schema 和配置文件分成证据块，再调用指定 Agent 生成供人工审核的候选知识库：

Claude Code CLI 是默认推理执行器。认证和模型路由由 Claude Code 自己解析，包括进程环境以及 `~/.claude/settings.json` 的 `env` 配置。若直接使用 Anthropic 官方 API，可以通过环境变量提供专用 Key：

```bash
export ANTHROPIC_API_KEY='your-api-key'
```

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --language zh-CN \
  --output-dir ./knowledge-output
```

使用 `--prepare-only` 可以只生成固定范围、完整文件清单、证据分块和提示词，不调用 Agent：

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --prepare-only
```

生成的 `candidate-knowledge.md` 明确处于未确认状态。CLI 不负责审批、更新或发布人工维护的正式知识库。

使用 `--language en` 或 `--language zh-CN` 可以切换分块观察和最终候选知识库的语言，缺省为英文。

只有覆盖默认 Claude Code 执行器、改用其他 CLI 或本地模型时，才需要传入 `--agent-command` 和可重复的 `--agent-arg`。repo-aware-reviewer 不读取、复制、记录或保存 Claude settings 中的凭据。如果配置了 `ANTHROPIC_BASE_URL`、`ANTHROPIC_AUTH_TOKEN` 或模型覆盖项，Claude Code 可能实际使用兼容网关和非 Claude 模型。

## 使用

单 commit，缺省审查 `HEAD^..HEAD`：

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --requirement /path/to/requirement.md \
  --knowledge /path/to/knowledge.md
```

分支相对共同祖先：

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch feature/example \
  --base main
```

显式范围：

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --range origin/main..feature/example
```

只准备证据，不调用模型：

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --prepare-only \
  --output-dir ./review-output
```

Agent 命令必须从标准输入读取 prompt，并把纯文本结果写到标准输出。CLI 不通过 shell 执行命令；`--agent-command` 和每个 `--agent-arg` 都作为独立参数传给 `ProcessBuilder`。

发现阶段可以请求基线或目标版本中已跟踪的文本，以及 `~/.m2/repository` 里单个 Java 类型或 `.proto` 条目。Maven 请求必须匹配所选版本根 `pom.xml` 中有明确版本的直接 JAR 依赖。CLI 记录制品哈希，用 `javap` 读取公开 Java 签名，补充证据会进入发给所配置后端的复核提示词。此过程无需构建或下载依赖。

## 输出

每次运行创建独立目录，其中包含：

- `scope.md`：解析后的审查范围和输入摘要；
- `evidence.md`：diff、目标文件内容、规则与需求证据；
- `coverage.md`：逐文件的 diff 覆盖和补充证据请求结果；
- `discover-prompt.md`：候选发现阶段提示词；
- `candidates.md`：候选问题；
- `supplemental-evidence.md`：提交给复核阶段的补充证据；
- `verify-prompt.md`：复核阶段提示词；
- `report.md`：最终报告。

## 当前原型边界

- 依赖读取当前覆盖本地已缓存的 Maven 直接 JAR 依赖，尚未解析父 POM/BOM、profile、传递依赖或其他包管理器，也尚未接入下载。
- 尚未执行构建、测试或静态检查命令。
- 尚未实现持久化仓库注册、知识库版本审批和 Skill/MCP 包装。
- Agent 输出当前为 Markdown；后续应增加结构化 findings schema。
