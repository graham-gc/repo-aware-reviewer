# repo-aware-reviewer

[English](README.md)

一个根据代码、需求和人工维护的业务知识库审查 Git 提交的 CLI。流程参考 Anthropic Code Review，由多个 Agent 独立审查，再逐条复核候选问题，汇总确认成立的问题。

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

默认 Claude 进程关闭内置工具和 MCP 工具，由本程序提供固定 Git 版本的证据并处理补读请求。认证与模型路由使用本机 Claude Code 配置。换用其他 Agent 时，用 `--agent-command` 指定可执行文件，每个参数各传一次 `--agent-arg`。该自定义命令决定自己的工具权限，从 stdin 读取 UTF-8 提示词，并把 UTF-8 回复写到 stdout。

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

`--knowledge` 接收一份 UTF-8 Markdown，无需固定排版。`--requirement` 接收单独的 UTF-8 需求文件，用于逐条验收。两个参数都可选，使用显式文件路径。

审查报告默认使用英语。加上 `--language zh-CN` 生成简体中文报告，`--language en` 生成英文报告。报告标题、说明、严重程度和需求验收状态使用所选语言，代码与源码路径保留原文。

加上 `--knowledge-mode scoped` 可按需加载主知识库。CLI 按 Markdown 标题保留完整章节，优先匹配改动的仓库路径、API 路径和代码标识符，并加载标题为 `全局规则` 或 `Global rules` 的章节。初审可针对具体的行为变化请求补读章节。知识库可以放在仓库外。如果同一份文件出现在本次 Git 变更中，CLI 会在 `coverage.md` 保留变更记录，并通过按需章节提供正文。默认的 `full` 模式会传入整份知识库。

- `--commit <ref>` 审查指定提交相对第一父提交的变更，默认目标是 `HEAD`。第一父提交须在本地可用。
- `--branch <目标引用> --base <基线引用>` 直接比较指定基线与目标的代码快照，等同于 `git diff BASE TARGET`。两者均接受分支名、标签或提交 ID，必须成对使用，不能与 `--commit` 同时使用。

CLI 默认读取本地已有的 Git 版本。需要在新审查开始前获取远端基线时，增加 `--base-remote`，并在 `--base` 中指定该远端上的分支名：

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch <目标提交ID> \
  --base main \
  --base-remote origin
```

其他基线可写成 `--base release/1.0`，`--base-remote upstream` 则指定另一个已配置的远端。使用 `--base-remote` 时，基线应写 `main` 或 `refs/heads/main`，而非本地跟踪引用 `origin/main`。目标提交仍须在本地可用。获取过程使用仓库已有的 Git 认证方式，并将取得的远端分支版本固定为本轮基线。它代表获取时的远端版本，不依赖合并方向或父提交顺序。审查仍直接比较这份快照与目标，目标尚未同步该基线时的差异也会纳入。获取失败会停止审查，不会回退到本地旧引用。

获取过程不会检出或合并代码，不会更新本地分支、远端跟踪引用或覆盖 `FETCH_HEAD`。每轮使用独立引用保护基线，审查结束后移除。获取期间也使用同一行动态进度。默认获取超时为 120 秒，可用 `--fetch-timeout-seconds` 调整。运行目录保留 `baseline-fetch-stdout.log`、`baseline-fetch-stderr.log`、`baseline-fetch-execution.log`（命令、工作目录、开始时间、超时、耗时及退出状态）和 `baseline-fetch-snapshot.log`（远端、分支、固定提交及获取时间）。`scope.md` 记录远端、分支及实际比较的提交。`--resume` 会复用原运行固定的基线，不会重新获取；需要更新基线时应开启新审查。

证据包中的仓库审查规则来自指定的基线提交；单次提交模式下则来自目标提交的第一父提交。

diff 为空时，程序直接生成本地报告，标明已跳过审查与需求验收，不调用模型。目标提交已经是远端基线最新提交时就会出现这种情况。需要审查该提交自身的改动，可使用 `--commit <ref>`；需要审查更大范围的变更，则指定更早的明确基线。

三个独立审查 Agent 分别侧重正确性与回归、需求与业务契约、边界条件与安全。程序合并并去重它们的补证请求。取得新证据后，增加一次检查以发现新的候选问题。每个候选问题随后单独调用模型复核，缺少关键证据时可以继续请求代码、依赖证据或知识库章节。

发布前，程序将讨论同一问题的候选归组，保留驳回和证据不足的意见，并对每个初步确认的候选独立裁决，核对同组证据及相反结论。发布裁决仍有分歧时，该问题保留待确认。确认问题必须说明与本次变更的关系，并引用实际提供的实现摘录；本次引入或触发的问题还需要基线或 diff 依据，未满足明确需求的问题则需要引用可信的需求或规则。与本次变更无关的既有缺陷不进入本次问题清单。

确认问题还须区分支持类型，并列出有证据的必要前提。对服务端响应、部署方式或输出目录复用的假设不能支撑已确认故障；需求明确要求拒绝畸形输入时，仍可确认防御要求未满足。原生文件证据记录稳定 ID、固定提交及真实行号，问题位置必须落在所引目标源码内。目录和通配符导航由通用仓库浏览器处理，旧的 Java 目录请求也有回退入口，避免被当作依赖坐标。

传入 `--requirement` 时，程序会单独进行逐项行为评估和独立审计，核对需求原文、实现证据、相关边界及缺失组件。最终需求验收和问题清单由程序渲染，汇总模型不能另行改写结论。原文摘录校验只能证明证据确实已提供，不能证明模型推理一定正确；所有结论仍是静态判断。

每项验收须说明判断标准是原文明示，还是尚未规定；未规定的策略不能得出确定性验收结论。独立审计会质疑加强后的转述、数字优先级及多种失败状态的重叠处理。`report.md` 展示简要验收清单，优先列出未满足和证据不足的事项；`requirement-assessment.md` 保留每项的完整依据、边界、规则说明及核准的源码位置。

`--review-parallelism` 控制审查和复核的并行调用数，默认是 `3`。设为 `1` 时，同样的任务依次执行。每次调用都使用已配置的 Agent 命令和超时，耗时及模型用量会随候选数量、发布裁决和补读轮次增加；传入需求文档还会增加行为评估和审计调用。没有新材料、请求重复、共用字节预算耗尽或达到已配置的请求限额时，补读停止。未解决的候选保持未确认，并列在报告中。

每个模型进程默认超时为 900 秒，可用 `--agent-timeout-seconds` 调整。审查任务会保存 `*-stdout.log`、`*-stderr.log` 和记录耗时、退出状态的 `*-execution.log`，命令超时时也会保留已收到的输出。进度计时按整个当前阶段累计，子任务完成时继续累计。

结构化回复支持原始 XML 和带代码围栏的 XML。协议文本字段中的说明和代码按文字处理，保留其中的标签、已有 XML 转义及 CDATA。单条问题的多余外层包装会先被移除，再进行字段和 ID 校验。结构错误时会自动尝试一次格式纠正，XML 错误反馈包含解析位置和具体原因；需求引文或证据引文与原文不一致时，还可单独尝试一次引文纠正，即使此前已纠正过格式，仍须通过原文校验。已提供的知识库章节可用唯一的 `KB-` ID 或省略行号后缀的标题引用。另有一次独立的结论纠正机会，用于重新评估证据不足的判断并降级结论，不把缺少证明当成格式问题。原回复和纠正记录都会保留。续跑中断的审查时，在原命令上增加 `--resume /path/to/run-directory`，程序会先检查原回复及已保存的纠正回复，复用有效结果，再调用模型继续缺失或无效的任务。实际审查范围和初始证据须与原运行一致；每个任务的提示词（含所提供的证据）也须一致才能复用，否则重新执行该任务。续跑时可以调整并行数。旧运行的证据格式不兼容时，需要重新运行。

## 编程语言支持

所有已识别的编程语言和文本类型均已独立模块化。程序根据源码及配置文件自动选择审查提示，混合语言变更可以同时启用多个模块；`--language` 仍表示报告的中英文语言。

| 模块 | 当前能力 |
| --- | --- |
| Java、Go | 文件证据、声明定位及各自已实现的依赖补读；Go 还提供包和 import 导航。 |
| Kotlin、Groovy、Scala、C#、F#、Python、JavaScript、TypeScript、Vue、Svelte、C/C++、Rust、Ruby、PHP | 基于文本的静态审查、相关项目配置、同目录文件导航和按范围补读源码。 |
| SQL、GraphQL、Protobuf、Thrift、Avro | 查询、接口或数据契约的审查提示，以及文本和文件导航。 |
| Shell、PowerShell、批处理、Make、CMake、Dockerfile | 脚本或构建配置的审查提示，以及文本和文件导航。 |
| 文档、结构化配置、表格数据 | 分别归类的格式模块和证据角色。 |

文本与导航模块尚未提供声明解析器、编译器类型检查、包或 import 解析、第三方依赖加载。缺少关键证据时保留审查缺口。`scope.md` 会记录已注册模块及其声明的能力。`CMakeLists.txt` 等明确文件名优先于通用 `.txt` 识别；导航元数据也不会当作实现证据。

Go 支持加载相关 `go.mod`、`go.sum`、`go.work` 和 vendor 元数据，提供包与文件索引、函数／类型／接收者方法定位，以及按需查询 import 消费方。可读取工作区内的相关包、固定版本中的本地替换、与配置一致的已跟踪 vendor，以及经过版本选择的模块缓存源码，包括生成的 protobuf 实体类。元数据缺失或版本无法确定时，保留证据缺口。静态支持无需安装 Go，也不会下载依赖或执行 Go 命令。

Go 缓存默认读取已导出的 `GOMODCACHE`，其次是已导出的 `GOPATH` 第一项下的 `pkg/mod`，最后使用 `~/go/pkg/mod`。可用 `--language-cache go=/path/to/pkg/mod` 指定。对于使用缓存的模块，可按已注册 ID 指定，例如 `--language-cache java=/path/to/maven-cache`。

Go 声明提取采用词法分析，尚未提供编译器类型检查、cgo 展开、标准库源码解析或构建标签／平台选择。包索引会保留测试和平台变体，不假定这些文件同时参与编译。基础语言模块采用独立的 `.properties` 配置，在 `META-INF/reviewagent/text-languages` 中注册即可，无需新增 Java 代码。专用辅助能力通过公开的 `LanguageModule` 接口和 Java ServiceLoader 注册，也可用相同 ID 替换基础配置模块。扩展方式、能力边界和 Go 解析限制见[语言模块指南](docs/language-modules.md)。

## GitHub PR 审查

把[示例工作流](examples/github-review.yml)复制到待审查仓库的 `.github/workflows/`，配置 `ANTHROPIC_API_KEY` Actions Secret，并将 Action 引用固定到可信版本。同仓库的 PR 创建或更新时会自动触发。有仓库写权限的人也可以在 PR 评论 `/review`。报告会回贴到 PR，报告、审查范围和覆盖情况也会保存为 Actions artifact。

把仓库变量 `REVIEW_KNOWLEDGE_PATH` 指向已跟踪的文件，例如 `docs/business-knowledge.md`，即可启用按需知识库。Action 从 PR 基线提交读取这份文件。可用 `requirement-path` 传入同一提交的需求文件，也可设 `publish-comment: 'false'`，只保存 artifact。工作流获取 base 和 head 的 Git 对象，在空工作树中运行 CLI，并关闭 Claude Code 工具。

Action 的 `language` 参数默认是 `en`。示例工作流从可选仓库变量 `REVIEW_LANGUAGE` 读取，也支持设为 `zh-CN`。

## 输出与限制

进度按已完成阶段显示百分比，写到 stderr，在交互式终端中原地刷新一行，重定向时按普通日志逐行输出。等待模型时只更新耗时，百分比保持不变。构建和测试脚本也使用百分比显示。两个命令成功后把本次运行目录写到 stdout。默认输出目录是 `review-output/` 和 `knowledge-output/`，可用 `--output-dir` 更改。任一命令加上 `--prepare-only`，即可在本地保存证据和提示词，无需调用模型。

- `review` 生成 `report.md`。`scope.md` 记录实际提交、输入哈希和并行设置，`evidence.md` 保存证据，`evidence-index.md` 记录来源 ID、类型、版本和可用位置，`coverage.md` 记录证据缺口及每个候选的复核状态。`discovery/`、`verification/`、`adjudication/` 和 `assessment/` 保存各次调用的提示词及回复，追加轮次保存在子目录。`supplemental-evidence.md` 保存各轮补读材料；传入需求文档时，`requirement-assessment.md` 保存摘要背后的完整验收明细。`verified-findings.md` 保存通过发布裁决、尚未去重的候选，`report-response.md` 保留汇总原文。格式纠正后仍未通过结构校验时，本轮审查会报错。
- `knowledge generate` 生成 `candidate-knowledge.md`。可查看 `inventory.md` 了解哪些文件被纳入或跳过。两种流程都会在本地保存各自的证据。

初审、复核、发布裁决和需求评估期间，采集器均可按请求读取基线或目标提交中已跟踪的文本。声明定位和依赖补证由已注册语言模块提供。Java 模块读取所选版本根 `pom.xml` 中声明了固定版本且已缓存的直接 JAR 依赖，提取公开签名和 `.proto` 文件；Go 模块按上述规则读取包源码。采集过程无需运行目标项目的构建或测试。输入较大时，`review` 可用 `--max-evidence-bytes`，`knowledge generate` 可用 `--max-file-bytes` 和 `--max-total-bytes` 调整预算。

代码和依赖补证请求、知识库查询默认不限条数，知识库摘录也不限制章节数量，证据仍受字节预算约束。可用 `--max-supplemental-requests <数量>` 限制文件与语言模块补证请求的合计条数（包含旧版 Maven 请求），用 `--max-knowledge-queries <数量>` 限制按需知识库查询条数。两个参数默认均为 `0`，表示不限条数。正数上限在所有阶段和轮次之间共用，按去重后实际尝试的请求计数，包含读取失败或未匹配的请求。重复请求不会重试，所有补读材料共用证据字节预算。`scope.md` 记录生效配置，`coverage.md` 记录跳过数量。

## 项目文档

根目录只提交文件名以 `README` 开头的 Markdown，大小写不限。其他项目文档放在 `docs/` 等子目录。
