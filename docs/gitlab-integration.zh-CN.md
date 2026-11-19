# GitLab CI/CD 接入

[English](gitlab-integration.md)

接入层将 GitLab 事件交给现有 Java 审查 CLI：

```text
业务项目 MR / 新增 /review 评论
    → Webhook 鉴权 → SQLite 持久队列
    → 在可信审查项目的受保护分支上触发流水线
    → 拉取固定的 base/head 对象 → 静态审查 CLI
    → 更新 MR 中同一条机器人汇总评论，并保存报告产物
```

支持 MR 创建、重新打开、推送代码、修改目标分支，以及取消草稿状态。新增一条内容恰好为 `/review` 的评论可再次触发；评论者必须是有效的 Developer 或更高角色。首版跳过跨 fork、已关闭和草稿 MR。仅目标分支推进时，不会自动重扫所有关联 MR；需要更新基线重新审查时可评论 `/review`。

基线取 **MR 目标分支当前的最新提交**，直接与 MR head 比较，不计算共同祖先。两端完整 SHA 会固定，并在执行前后重新检查。排队期间过期的任务跳过；执行期间过期的结果保留为产物，评论只显示 `STALE`，不回写旧版本问题。最终检查后仍可能发生新提交，因此应以报告标明的 SHA 为准。

## 1. 构建两个镜像

在本工具项目根目录执行，需要 Docker 正常运行：

```bash
docker build -f integrations/gitlab/Dockerfile --target reviewer \
  -t registry.example.com/repo-aware-reviewer:gitlab-v1 .
docker build -f integrations/gitlab/Dockerfile --target webhook \
  -t registry.example.com/repo-aware-webhook:gitlab-v1 .
```

将镜像发布到自己的仓库，后续配置使用固定 digest。审查镜像包含 Java 21、Git、Python 和 Claude Code `2.1.325`；可用 `--build-arg CLAUDE_CODE_VERSION=...` 更换 CLI 版本。Webhook 镜像仅含 Python 接入层。两个镜像均以 UID/GID `10001` 运行。

## 2. 配置可信审查项目

建立一个**私有 GitLab 审查项目**，使用 `main` 等受保护分支。把 [流水线示例](../examples/gitlab-review.yml) 复制为该分支的 `.gitlab-ci.yml`。准备能够拉取审查镜像的 Docker 或 Kubernetes GitLab Runner，并允许两小时的任务时限。审查项目和产物的访问人员应具备读取相应业务代码的权限。

审查配置与凭据由该项目维护，不执行被审查 MR 中的 CI 配置。示例的 `resource_group` 会让同一个项目/MR 的审查任务串行执行，调整模板时应保留。

在可信项目设置中，允许 Trigger Token 所属账号运行该受保护分支并传入流水线变量。若安装环境禁用了变量覆盖，应明确允许该账号的角色，见 [流水线变量权限](https://docs.gitlab.com/ci/variables/#restrict-pipeline-variables)。共用模型网关时，还需按网关容量设置 Runner 并发；同 MR 串行不等于所有 MR 串行。

在可信项目中配置下列 CI/CD 变量。凭据设为 masked、protected。使用有权访问业务项目的专用机器人账号；其 `api` scope 的个人访问令牌可用于 API、HTTPS 读取仓库和回写 MR 评论，账号权限也必须允许这些操作。参见 [GitLab Token 认证说明](https://docs.gitlab.com/user/profile/personal_access_tokens/)。

| 变量 | 配置内容 |
| --- | --- |
| `REVIEW_AGENT_IMAGE` | 已发布的审查镜像，固定 digest。 |
| `GITLAB_URL` | GitLab 地址，例如 `https://gitlab.example.com`，不带 `/api/v4`。 |
| `GITLAB_TOKEN` | 机器人访问令牌，不是流水线 Trigger Token。 |
| `GITLAB_REVIEW_PROJECT_IDS` | 允许审查的业务项目数字 ID，例如 `123` 或 `123,456`。 |
| `GITLAB_REVIEW_BOT_USER_ID` | 可选，机器人用户数字 ID，会与写入令牌的身份核对。 |
| 模型连接变量 | Claude Code 后端所需的环境变量，见下文。 |
| `REVIEW_MODE` | 默认 `auto`，可设为 `deep`，让每个非空 MR 都走完整的深审流程。 |
| `REVIEW_KNOWLEDGE_FILE` | 可选，**File 类型变量**，值为人工批准的 UTF-8 Markdown 主知识库内容。 |
| `REVIEW_REQUIREMENT_FILE` | 可选，**File 类型变量**，值为 UTF-8 需求文档内容。 |

GitLab 将 File 变量转换为临时文件路径，适配层读取此路径；也可直接使用挂载文件。知识库和需求**不必提交到任何仓库**；只接收一份主知识库，按需加载。单份文档上限 4 MiB。建议先接一个业务项目；接多个项目时，应在可信配置中分别指定对应的业务文档。

GitLab 自身的变量长度限制仍然有效，目前项目/组变量值上限为 10,000 个字符；大文档应使用挂载文件。适配层的 4 MiB 上限不会扩大 GitLab 的变量上限。

另一种方式是配置 `REVIEW_KNOWLEDGE_PATH` / `REVIEW_REQUIREMENT_PATH`，读取**已固定的目标基线提交**中的仓库相对路径，不读取 feature 分支里的版本。对应的 `_FILE` 与 `_PATH` 不可同时设置；仓库中的符号链接文档会被拒绝。

### CI 中如何连接模型

仍使用 **Claude Code CLI**，没有另写直连 Anthropic 的客户端。CI 使用 `--bare`，禁用模型工具和 MCP，在被审查工作区之外运行。通过 CI 环境提供认证与模型路由，例如直连 API 使用 `ANTHROPIC_API_KEY`；已有网关可使用其能正常工作的 `ANTHROPIC_BASE_URL`、`ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_MODEL` 配置。能否兼容取决于网关对固定 CLI 版本的支持，应先在 CI 环境验证连接。

容器不会自动继承开发者电脑上的 Claude 登录或 `~/.claude/settings.json`；bare 模式不使用订阅 OAuth/钥匙串凭据。见 [Claude Code bare 模式说明](https://code.claude.com/docs/en/headless#start-faster-with-bare-mode)。本地 CLI 的使用方式不变。

### 可选任务配置

| 变量 | 默认值 / 作用 |
| --- | --- |
| `REVIEW_LANGUAGE` | `en`，也支持 `zh-CN`；项目 CI 变量可覆盖示例默认值。 |
| `REVIEW_PARALLELISM` | 本接入默认 `1`，降低同时请求模型的压力。 |
| `REVIEW_TIMEOUT_SECONDS` | `5400`，整个 CLI 的超时，应为 CI 截止前的收尾留出时间。 |
| `REVIEW_AGENT_TIMEOUT_SECONDS` | CLI 默认值，当前为单次模型调用 `900` 秒。 |
| `REVIEW_AGENT_RETRIES` | CLI 默认值，当前最多额外重试 `1` 次，须满足进程清理条件。 |
| `REVIEW_MAX_EVIDENCE_BYTES` | 使用 CLI 默认值，可显式调整证据预算。 |
| `REVIEW_PUBLISH_COMMENT` | `true`；明确设为 `false` 时只生成产物，不回写评论。 |
| `GITLAB_CA_FILE` | 可选，自定义 GitLab CA 的挂载路径，用于 API 和 Git HTTPS。 |

CLI 执行期间，任务日志每 30 秒输出一次运行提示。传给 Java/Claude 的环境会去除 `GITLAB_*`、`CI_*`、`GIT_*` 变量，保留模型路由配置。不执行目标项目的构建、测试、依赖安装脚本、项目钩子或业务 CI 命令。

## 3. 部署 Webhook 服务

在可信审查项目中创建 Pipeline Trigger Token。通过密钥管理工具或名为 `repo-aware-gitlab` 的 Kubernetes Secret 提供以下变量，不要将真实凭据填进仓库示例。

| 变量 | 配置内容 |
| --- | --- |
| `GITLAB_URL` | 与审查任务相同的 GitLab 地址。 |
| `GITLAB_READ_TOKEN` | 可读取可信项目和允许业务项目 API 的令牌；省略则使用 `GITLAB_TOKEN`。 |
| `GITLAB_REVIEW_PROJECT_IDS` | 相同的业务项目数字 ID 白名单。 |
| `GITLAB_REVIEW_BOT_USER_ID` | **回写评论的机器人**的用户 ID；只读令牌属于其他账号时尤其需要明确配置。 |
| `GITLAB_PIPELINE_PROJECT_ID` | 可信审查项目的数字 ID。 |
| `GITLAB_PIPELINE_REF` | 其受保护分支，例如 `main`，启动时会检查。 |
| `GITLAB_TRIGGER_TOKEN` | 该可信项目的 Pipeline Trigger Token。 |
| `GITLAB_WEBHOOK_SECRET` | 至少 24 个字符的随机密钥。 |

[Kubernetes 清单](../integrations/gitlab/k8s/webhook.yaml) 提供单副本、持久化 SQLite 卷、健康检查及 ClusterIP Service。替换镜像，预先创建 Secret，并确保卷允许 UID/GID `10001` 写入，再部署清单。私有镜像仓库还需配置拉取凭据。自行配置 TLS Ingress/反向代理，将 `/webhook` 路由到服务；不要以明文公网地址提供入口，并配置常规请求及并发限制。服务在代理后监听 HTTP `8080`；`/healthz` 检查队列可用性。GitLab 使用自定义 CA 时，此服务也须挂载证书并配置 `GITLAB_CA_FILE`。

SQLite 清单只能运行 **一个 Webhook 副本**，重启须保留持久卷。多副本需要更换状态后端，本清单不支持。

在每个允许的业务项目中新增 Webhook：

- URL：`https://your-review-webhook.example.com/webhook`。
- Secret Token：与 `GITLAB_WEBHOOK_SECRET` 一致。
- 勾选 **Merge request events** 和 **Comments**。
- 保持 SSL 验证开启。

入口先校验密钥并持久化归一后的事件，立即返回；后台再通过 GitLab API 检查当前 MR 和评论者权限，触发可信流水线。任务也会重新检查手动命令权限。机器人自身评论不会再次触发审查；Webhook 服务不需要模型凭据。

事件及鉴权依据 [GitLab Webhook 事件文档](https://docs.gitlab.com/user/project/integrations/webhook_events/) 和 [Webhook 认证文档](https://docs.gitlab.com/user/project/integrations/webhooks/)，创建流水线使用 [Trigger API](https://docs.gitlab.com/api/pipeline_triggers/)。

## 4. 使用与排查

创建非草稿 MR 或推送新提交即可触发。需要重跑时，**新发一条**仅含 `/review` 的评论，编辑旧评论不会重跑。也可在可信项目的 GitLab 界面选择受保护分支手动运行流水线，传入数字 `REVIEW_PROJECT_ID` 和 `REVIEW_MR_IID`。未指定预期 SHA 时，任务启动时固定当前基线和 MR head。

机器人更新自己带标记的汇总评论，不会每次追加完整新报告；其他用户伪造同样标记的评论会被忽略。超过评论长度预算的完整报告放在产物中。上传内容仅包括报告、范围、覆盖、耗时、执行元数据、任务检查点和 `integration-status.json`，不上传原始提示词、证据包、原始错误及模型 stdout/stderr。任务 `*-state.properties` 保留相对运行目录的原路径，确保未完成报告中的链接在导出后仍可访问；其中仅有固定的错误类别、摘要及可用的 XML 解析位置，没有原始异常文本。执行日志及安全的 `*-validation.log` 校验历史放在 `diagnostics/` 下，成功纠错后仍保留历史类别。报告仍可能含代码摘录；产物保留七天，示例通过 [artifact access](https://docs.gitlab.com/ci/yaml/#artifactsaccess) 限制 Developer 或更高角色下载。

| 任务结果 | 含义 |
| --- | --- |
| 退出 `0`，`COMPLETE` | 审查执行完成，仍可能发现已验证的问题。 |
| 退出 `0`，`SKIPPED` / `STALE` | 没有针对当前版本的有效结论，应检查状态文件。 |
| 退出 `3`，`INCOMPLETE` | 部分检查未完成；任务失败，保留可用报告。 |
| 退出 `1` / 超时 / 取消 | 执行失败或停止，不代表代码通过。 |

首版不自动批准 MR、不阻止合并、不按问题数量实施门禁，也不发布行内讨论。它提供审查建议流水线，不是合并门禁。Runner 强制终止可能导致最后的评论和产物无法回传；若评论停留在 `RUNNING`，应查看实际 CI 任务状态。网络或 API 故障也可能使回写失败。

自动任务按项目/MR/base/head 去重，手动任务按评论 ID 去重，SQLite 队列跨重启保存。**不承诺严格只执行一次**：流水线创建请求发出后若超时，无法确定是否创建成功，不会自动重复 POST。日志/状态标为 `UNKNOWN`；领取任务后崩溃也可能留下 `CLAIMED`。先检查 GitLab 流水线，再重试已有 CI 任务、手动运行可信流水线或新发 `/review`。不要为了重跑删除持久卷；失败的自动快照不会被无限自动重排。

## 本地验证

```bash
bash scripts/build.sh
bash scripts/test-gitlab.sh
bash scripts/test.sh
```

只有 GitLab 接入层需要 Python 3.12+。测试使用本地假 GitLab API、临时 Git 仓库和假 `claude`，构建 JAR 后会运行真实 CLI 的接入测试，不连接真实模型或 GitLab。镜像构建和运行、目标 GitLab 版本的 CI Lint，以及测试 MR 的实际联调，属于后续部署验证。
