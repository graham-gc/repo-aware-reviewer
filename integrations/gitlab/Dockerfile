# Build each target explicitly: reviewer for CI, webhook for the durable event bridge.
FROM node:22-bookworm-slim AS node-runtime
FROM eclipse-temurin:21-jdk-noble AS reviewer
COPY --from=node-runtime /usr/local/ /usr/local/
RUN apt-get update && apt-get install -y --no-install-recommends git python3 ca-certificates libstdc++6 curl \
    && rm -rf /var/lib/apt/lists/*
ARG CLAUDE_CODE_VERSION=2.1.325
RUN npm install --global "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" && claude --version
WORKDIR /opt/repo-aware
COPY src/main/ src/main/
COPY scripts/build.sh scripts/compile.sh scripts/dependencies.sh scripts/progress.sh scripts/
RUN bash scripts/build.sh
COPY integrations/gitlab/repo_aware_gitlab/ /opt/gitlab/repo_aware_gitlab/
ENV PYTHONPATH=/opt/gitlab PYTHONDONTWRITEBYTECODE=1
RUN groupadd --gid 10001 reviewer && useradd --uid 10001 --gid 10001 --create-home reviewer \
    && mkdir /workspace && chown reviewer:reviewer /workspace
USER 10001:10001
WORKDIR /workspace
# GitLab Runner supplies its own script; no forced ENTRYPOINT interferes with the runner shell.
CMD ["python3", "-m", "repo_aware_gitlab.review"]

FROM python:3.12-slim-bookworm AS webhook
WORKDIR /opt/gitlab
COPY integrations/gitlab/repo_aware_gitlab/ /opt/gitlab/repo_aware_gitlab/
RUN groupadd --gid 10001 reviewer && useradd --uid 10001 --gid 10001 --create-home reviewer \
    && mkdir /data && chown reviewer:reviewer /data
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 GITLAB_STATE_DB=/data/gitlab-events.sqlite3
USER 10001:10001
EXPOSE 8080
CMD ["python3", "-m", "repo_aware_gitlab.webhook"]
