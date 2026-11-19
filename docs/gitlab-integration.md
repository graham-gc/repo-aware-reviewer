# GitLab CI/CD integration

[中文](gitlab-integration.zh-CN.md)

This adapter connects GitLab events to the existing Java review CLI:

```text
Application project MR / new /review comment
    → authenticated webhook → durable SQLite inbox
    → pipeline on a protected branch in a trusted review project
    → fetch fixed base/head objects → static review CLI
    → update one bot summary on the MR + upload report artifacts
```

It supports opened/reopened MRs, code updates, target-branch changes and leaving draft status. A new comment containing exactly `/review` requests another run; its author must be an active Developer or higher. Fork MRs, closed MRs and drafts are skipped. Updating only the target branch does not automatically rescan all its open MRs; post `/review` when a new baseline review is needed.

The baseline is the **current target branch tip**, compared directly with the MR head. Both full SHAs are pinned and rechecked before and after analysis. This is not a merge-base comparison. An outdated queued job is skipped; results that become outdated during review are kept as artifacts, with a `STALE` notice instead of findings in the comment. A branch can still change after the final check: always read the displayed SHAs.

## 1. Build the two images

From this repository's root, with Docker running:

```bash
docker build -f integrations/gitlab/Dockerfile --target reviewer \
  -t registry.example.com/repo-aware-reviewer:gitlab-v1 .
docker build -f integrations/gitlab/Dockerfile --target webhook \
  -t registry.example.com/repo-aware-webhook:gitlab-v1 .
```

Publish these images to your registry and pin their digests in the configuration below. The reviewer image includes Java 21, Git, Python and Claude Code `2.1.325`; `--build-arg CLAUDE_CODE_VERSION=...` changes the CLI version. The webhook image contains only the Python adapter. Both run as UID/GID `10001`.

## 2. Configure a trusted review project

Create a **private GitLab project for review pipelines** with a protected branch such as `main`. Copy [the pipeline example](../examples/gitlab-review.yml) to that project's `.gitlab-ci.yml` on this branch. Use a Docker or Kubernetes GitLab Runner that can pull the reviewer image. The runner must allow jobs up to two hours. Keep access to this project and its artifacts limited to people allowed to see the reviewed code.

The review configuration and credentials belong to this trusted project. The application MR's CI configuration is not executed. `resource_group` serializes jobs for the same project/MR; keep it when adapting the template.

Allow the trigger-token owner to run this protected branch and supply pipeline variables in the trusted project's settings. An installation that disables pipeline variable overrides must explicitly permit this account's role; see [pipeline variable restrictions](https://docs.gitlab.com/ci/variables/#restrict-pipeline-variables). For a shared model gateway, also set the runner's concurrency to match its capacity: per-MR serialization does not serialize different MRs.

Configure these CI/CD variables on the trusted project. Mark credentials masked and protected. Use a dedicated bot account with access to the application projects. A bot personal access token with `api` scope provides API access, HTTPS repository reads and MR note writes; scope and membership must permit all these operations. See [GitLab token authentication](https://docs.gitlab.com/user/profile/personal_access_tokens/).

| Variable | Value |
| --- | --- |
| `REVIEW_AGENT_IMAGE` | Published reviewer image pinned by digest. |
| `GITLAB_URL` | GitLab installation URL, e.g. `https://gitlab.example.com`, without `/api/v4`. |
| `GITLAB_TOKEN` | Bot access token, not a pipeline trigger token. |
| `GITLAB_REVIEW_PROJECT_IDS` | Allowed application project numeric IDs, e.g. `123` or `123,456`. |
| `GITLAB_REVIEW_BOT_USER_ID` | Optional bot numeric user ID; checked against the write token. |
| Model connection variables | The environment needed by your Claude Code backend; see below. |
| `REVIEW_KNOWLEDGE_FILE` | Optional **File-type variable** containing the human-approved UTF-8 Markdown knowledge base. |
| `REVIEW_REQUIREMENT_FILE` | Optional **File-type variable** containing the UTF-8 requirements document. |

The two File-type variable names are passed as **paths supplied by GitLab**, not document contents. A mounted file can be used instead. Documents do not need to be committed to either repository; one knowledge file is accepted and loaded in scoped mode. Each document is limited to 4 MiB. Start with one application project; for multiple projects, configure the appropriate trusted documents per project.

GitLab's own CI variable size limits still apply (currently 10,000 characters for project/group variable values). Use mounted files for larger documents; the adapter's 4 MiB limit does not enlarge GitLab's variable limit.

Alternatively, `REVIEW_KNOWLEDGE_PATH` / `REVIEW_REQUIREMENT_PATH` select repository-relative regular files from the **pinned target baseline**, never the feature branch. A `_FILE` and its matching `_PATH` cannot both be set. Symlinks in the repository are rejected.

### Model connection in CI

The adapter still uses **Claude Code CLI** and does not introduce a direct Anthropic API client. CI uses `--bare`, disables model tools and MCP servers, and runs outside the reviewed worktree. Supply authentication and routing through the CI environment: for example `ANTHROPIC_API_KEY` for a direct API connection, or your working gateway's `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` and `ANTHROPIC_MODEL` configuration. Compatibility depends on that backend supporting the pinned Claude Code CLI; test the connection in your CI environment first.

This container does not inherit the developer laptop's Claude login or `~/.claude/settings.json`. Bare mode does not use subscription OAuth/keychain credentials. See [Claude Code's bare-mode documentation](https://code.claude.com/docs/en/headless#start-faster-with-bare-mode). Local CLI usage remains unchanged.

### Optional job settings

| Variable | Default / meaning |
| --- | --- |
| `REVIEW_LANGUAGE` | `en`; also supports `zh-CN`. Set a project CI variable to override the template. |
| `REVIEW_PARALLELISM` | `1` in this integration; avoids concurrent model pressure by default. |
| `REVIEW_MODE` | `auto` by default. Set `deep` to run the full workflow for every nonempty MR. |
| `REVIEW_TIMEOUT_SECONDS` | `5400`, total CLI process timeout; must leave time before the CI job deadline. |
| `REVIEW_AGENT_TIMEOUT_SECONDS` | CLI default, currently `900` per model call. |
| `REVIEW_AGENT_RETRIES` | CLI default, currently `1` extra transport retry subject to cleanup checks. |
| `REVIEW_MAX_EVIDENCE_BYTES` | CLI default; optional explicit evidence budget. |
| `REVIEW_PUBLISH_COMMENT` | `true`; set exactly `false` to produce artifacts without posting comments. |
| `GITLAB_CA_FILE` | Optional mounted custom CA certificate path for GitLab API and Git HTTPS. |

The job emits a heartbeat every 30 seconds while the CLI is running. `GITLAB_*`, `CI_*` and `GIT_*` variables are removed from the Java/Claude process environment; model routing variables remain available. No build, test, install script, project hook or application CI command is executed.

## 3. Deploy the webhook bridge

Create a pipeline trigger token in the trusted review project. The webhook service needs the following environment, supplied using your secret manager or a Kubernetes Secret named `repo-aware-gitlab`; never put real tokens in these tracked examples.

| Variable | Value |
| --- | --- |
| `GITLAB_URL` | Same GitLab installation as the review job. |
| `GITLAB_READ_TOKEN` | Token with API read access to the trusted project and allowed application projects. If omitted, `GITLAB_TOKEN` is used. |
| `GITLAB_REVIEW_PROJECT_IDS` | Same numeric application-project allowlist. |
| `GITLAB_REVIEW_BOT_USER_ID` | User ID of the **comment-writing bot**, especially when the read token belongs to a different account. |
| `GITLAB_PIPELINE_PROJECT_ID` | Numeric ID of the trusted review project. |
| `GITLAB_PIPELINE_REF` | Its protected branch, e.g. `main`; checked at service startup. |
| `GITLAB_TRIGGER_TOKEN` | Pipeline trigger token for that trusted project. |
| `GITLAB_WEBHOOK_SECRET` | Random secret of at least 24 characters. |

[Kubernetes manifest](../integrations/gitlab/k8s/webhook.yaml) provides a single replica, persistent SQLite volume, health probes and ClusterIP service. Replace the image placeholder, provision the Secret and ensure the volume is writable by UID/GID `10001`, then apply it. Configure image pull credentials if your registry requires them. Add your own TLS ingress/reverse proxy to route `/webhook`; do not expose the endpoint without TLS. Apply normal ingress request/concurrency limits. The service itself listens on HTTP port `8080` behind that ingress; `/healthz` checks its inbox. A custom GitLab CA needs a mounted certificate and `GITLAB_CA_FILE` in this service too.

Keep **one webhook replica** with this SQLite deployment. The persistent volume is required across restarts. Multiple replicas require replacing the state backend; they are not supported by this manifest.

In each allowed application project, add a GitLab webhook:

- URL: `https://your-review-webhook.example.com/webhook`.
- Secret token: the same `GITLAB_WEBHOOK_SECRET`.
- Events: **Merge request events** and **Comments**.
- Keep SSL verification enabled.

The endpoint validates the secret, normalizes the event, writes it to the inbox and returns promptly. A worker validates the current MR and comment author's membership through the GitLab API before triggering the trusted pipeline. The job rechecks manual-command permission as well. Bot comments do not trigger another review. The service does not need model credentials.

Webhook event fields and the secret header follow [GitLab webhook events](https://docs.gitlab.com/user/project/integrations/webhook_events/) and [webhook authentication](https://docs.gitlab.com/user/project/integrations/webhooks/); pipeline creation uses the [trigger API](https://docs.gitlab.com/api/pipeline_triggers/).

## 4. Use and inspect

Open a non-draft MR or push new commits. To rerun, post a **new** comment containing only `/review`. Editing an old comment does not rerun it. You can also run the trusted pipeline from GitLab's UI on its protected branch with `REVIEW_PROJECT_ID` and `REVIEW_MR_IID`; both are numeric IDs. The job snapshots the current target branch and MR head when no expected SHAs are supplied.

The bot updates its own marked summary rather than appending a new report each time. Markers copied into other users' notes are ignored. Full reports above the comment size budget are available as artifacts. The job uploads only reports, scope, coverage, timings, execution metadata, task checkpoints and `integration-status.json`; raw prompts, evidence packs, raw errors and model stdout/stderr are not uploaded. Task `*-state.properties` keep their run-relative paths so incomplete-report links remain usable after export. They contain fixed error categories/summaries and optional XML parser positions, not raw exception text. Execution logs and safe `*-validation.log` histories remain under `diagnostics/`; validation histories survive successful corrections. Reports can still contain source excerpts. Artifacts expire after seven days; the template restricts downloads to Developer or higher using [artifact access](https://docs.gitlab.com/ci/yaml/#artifactsaccess).

| Job result | Meaning |
| --- | --- |
| Exit `0`, `COMPLETE` | Review execution completed; verified findings may still exist. |
| Exit `0`, `SKIPPED` / `STALE` | No current review conclusion; inspect the integration status. |
| Exit `3`, `INCOMPLETE` | Some checks could not finish; the job fails and retains available reports. |
| Exit `1` / timeout / cancellation | Execution failed or stopped; do not treat it as approval. |

This version does not approve MRs, prevent merges, enforce a findings policy or post inline discussions. It is an advisory review pipeline, not a merge gate. Hard CI runner termination can prevent the final comment/artifact upload; inspect the actual CI job state if a comment remains `RUNNING`. Network/API failure can also prevent comment updates.

Automatic reviews deduplicate by project/MR/base/head; manual reviews deduplicate by comment ID. The SQLite inbox survives restarts. It is **not an exactly-once delivery guarantee**: if pipeline creation times out after being sent, its outcome is unknown and it is not automatically repeated. The logs/state record `UNKNOWN`; a crash after claiming a review can leave `CLAIMED`. Check GitLab pipelines first, then retry that CI job, run a trusted pipeline manually, or post a fresh `/review` comment. Do not delete the state volume just to force retries. A failed automatic snapshot is not automatically requeued indefinitely.

## Local verification

```bash
bash scripts/build.sh
bash scripts/test-gitlab.sh
bash scripts/test.sh
```

Python 3.12+ is needed only for this adapter. Its tests use a local fake GitLab API, temporary Git repositories and a fake `claude` executable. The built-JAR integration test runs the real CLI without contacting a model or a real GitLab server. Building/running images, CI Lint on your GitLab version and a staging MR test are separate deployment checks.
