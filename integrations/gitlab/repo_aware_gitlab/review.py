"""Run the packaged CLI in an empty worktree and update one bot-owned MR summary."""
from __future__ import annotations

import base64
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

from .api import IntegrationError, Skip, api_from_env, authorize_note, positive, project_ids, required, sha

PUBLIC_FILES = ("report.md", "review-status.md", "scope.md", "coverage.md", "timings.md", "requirement-assessment.md")


def run_git(repo, arguments, *, env=None, timeout=120):
    try:
        result = subprocess.run(["git", "-C", str(repo), *arguments], env=env, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise IntegrationError("Git operation could not complete; credentials are omitted from diagnostics") from None
    if result.returncode:
        raise IntegrationError("Git operation failed; check repository access and ref availability")
    return result.stdout


def fetch_snapshot(api, snapshot, root, env):
    repo = root / "repo"
    repo.mkdir()
    git_env = {key: value for key, value in env.items() if not key.startswith("GIT_")}
    git_env.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0"})
    run_git(repo, ["init", "--quiet", "--template="], env=git_env)
    run_git(repo, ["check-ref-format", "refs/heads/" + snapshot.target_branch], env=git_env)
    project = api.get(f"/projects/{snapshot.project}")
    if positive(project.get("id")) != snapshot.project:
        raise IntegrationError("Project response identity mismatch")
    remote = api.trusted_url(project.get("http_url_to_repo", ""))
    authorization = base64.b64encode(("oauth2:" + api.token).encode()).decode()
    settings = [(f"http.{api.url}/.extraheader", "AUTHORIZATION: Basic " + authorization),
                ("http.followRedirects", "false"), ("protocol.file.allow", "never")]
    if env.get("GITLAB_CA_FILE"):
        settings.append(("http.sslCAInfo", env["GITLAB_CA_FILE"]))
    git_env["GIT_CONFIG_COUNT"] = str(len(settings))
    for i, (key, value) in enumerate(settings):
        git_env[f"GIT_CONFIG_KEY_{i}"] = key
        git_env[f"GIT_CONFIG_VALUE_{i}"] = value
    run_git(repo, ["fetch", "--quiet", "--no-tags", "--no-write-fetch-head", "--", remote,
                   f"+refs/merge-requests/{snapshot.iid}/head:refs/review/head",
                   f"+refs/heads/{snapshot.target_branch}:refs/review/base"], env=git_env)
    base = run_git(repo, ["rev-parse", "refs/review/base"], env=git_env).decode().strip()
    head = run_git(repo, ["rev-parse", "refs/review/head"], env=git_env).decode().strip()
    if (base, head) != (snapshot.base, snapshot.head):
        raise Skip("Branches changed while Git objects were fetched")
    return repo


def baseline_file(repo, commit, path, destination):
    if not path or path.startswith("/") or any(piece in ("", ".", "..") for piece in path.split("/")):
        raise IntegrationError("Document paths must be repository-relative regular file paths")
    entry = run_git(repo, ["--literal-pathspecs", "ls-tree", "--full-tree", "--format=%(objectmode) %(objecttype) %(objectname)", commit, "--", path]).decode().strip()
    match = re.fullmatch(r"100(?:644|755) blob ([0-9a-f]{40})", entry)
    if not match:
        raise IntegrationError("Configured business document is not a regular file at the pinned baseline")
    size = int(run_git(repo, ["cat-file", "-s", match[1]]))
    if size > 4 * 1024 * 1024:
        raise IntegrationError("Business document exceeds the integration size limit")
    content = run_git(repo, ["cat-file", "blob", match[1]])
    if len(content) > 4 * 1024 * 1024:
        raise IntegrationError("Business document exceeds the integration size limit")
    content.decode("utf-8", errors="strict")
    destination.write_bytes(content)
    return destination


def agent_environment(env):
    # Model credentials/routing stay available. GitLab and Git-fetch credentials do not reach Java/Claude.
    return {key: value for key, value in env.items() if not key.startswith(("GITLAB_", "CI_", "GIT_"))}



def execute_cli(arguments, cwd, env, stdout, stderr, timeout):
    process = subprocess.Popen(arguments, cwd=cwd, env=env, stdout=stdout, stderr=stderr, start_new_session=True)
    def stop():
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                return
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=10)
    old_handlers = {}
    def interrupted(signum, _):
        stop()
        raise KeyboardInterrupt
    if threading.current_thread() is threading.main_thread():
        for signum in (signal.SIGTERM, signal.SIGINT):
            old_handlers[signum] = signal.signal(signum, interrupted)
    try:
        started = time.monotonic()
        while True:
            remaining = timeout - (time.monotonic() - started)
            if remaining <= 0:
                stop()
                return 124
            try:
                return process.wait(timeout=min(30, remaining))
            except subprocess.TimeoutExpired:
                print(f"Static review is running ({int(time.monotonic() - started)}s elapsed).", flush=True)
    finally:
        stop()
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)


class Publisher:
    def __init__(self, api, snapshot, bot):
        self.api, self.snapshot, self.bot = api, snapshot, bot
        self.marker = f"<!-- repo-aware-reviewer:gitlab:{snapshot.project}:{snapshot.iid} -->"
        self.current = next((note for note in api.notes(snapshot) if note.get("author", {}).get("id") == bot
                             and note.get("body", "").startswith(self.marker + "\n")), None)

    def completed(self, request):
        if not self.current:
            return False
        header = self.current.get("body", "").splitlines()[:2]
        expected = f"<!-- snapshot:{self.snapshot.base}:{self.snapshot.head};status:COMPLETE;request:{request} -->"
        return len(header) == 2 and header[1] == expected

    def write(self, status, request, report, language, job_url):
        snap = self.snapshot
        title = "代码审查" if language == "zh-CN" else "Code review"
        notice = "完成状态仅表示审查执行情况，不代表代码验收通过。" if language == "zh-CN" else "Execution status is not code approval."
        header = self.marker + f"\n<!-- snapshot:{snap.base}:{snap.head};status:{status};request:{request} -->\n"
        header += f"## {title} · {status}\n\nBase `{snap.base}` · Head `{snap.head}`\n\n{notice}\n\n"
        if job_url:
            header += f"[CI job / artifacts]({job_url})\n\n"
        if len((header + report).encode()) > 60000:
            report = "完整报告请查看 CI 产物。" if language == "zh-CN" else "The report exceeds the comment budget; open the CI job artifacts for the full report."
        path = f"/projects/{snap.project}/merge_requests/{snap.iid}/notes"
        if self.current:
            path += "/" + str(positive(self.current["id"]))
        note = self.api.request("PUT" if self.current else "POST", path, {"body": header + report})[0]
        self.current = note


def review(env=os.environ, *, api=None, fetcher=fetch_snapshot, runner=execute_cli):
    api = api or api_from_env(env)
    allowed = project_ids(required(env, "GITLAB_REVIEW_PROJECT_IDS"))
    project, iid = positive(required(env, "REVIEW_PROJECT_ID")), positive(required(env, "REVIEW_MR_IID"))
    if project not in allowed:
        raise IntegrationError("Review project is not allowlisted")
    configured_output = Path(env.get("REVIEW_OUTPUT_DIR", "review-output"))
    if configured_output.is_symlink():
        raise IntegrationError("Output paths must not be symbolic links")
    output = configured_output.resolve()
    public = output / "public"
    if output.is_symlink() or public.is_symlink():
        raise IntegrationError("Output paths must not be symbolic links")
    if public.exists() and any(public.iterdir()):
        if not (public / ".repo-aware-public").is_file():
            raise IntegrationError("Choose an unused integration output directory")
        shutil.rmtree(public)
    public.mkdir(parents=True, exist_ok=True)
    (public / ".repo-aware-public").touch()
    summary = {"project": project, "iid": iid, "status": "FAILED"}
    publisher = None
    analysis_started = False
    language = env.get("REVIEW_LANGUAGE", "en")
    if language not in ("en", "zh-CN"):
        raise IntegrationError("REVIEW_LANGUAGE must be en or zh-CN")
    try:
        snapshot = api.snapshot(project, iid)
        for field, actual in (("REVIEW_EXPECTED_BASE", snapshot.base), ("REVIEW_EXPECTED_HEAD", snapshot.head)):
            if env.get(field) and sha(env[field]) != actual:
                raise Skip("Queued review was superseded by a different base or head")
        bot = api.identity()
        if env.get("GITLAB_REVIEW_BOT_USER_ID") and positive(env["GITLAB_REVIEW_BOT_USER_ID"]) != bot:
            raise IntegrationError("Configured report bot does not match the GitLab write token")
        trigger = env.get("REVIEW_TRIGGER", "auto")
        if trigger not in ("auto", "manual"):
            raise IntegrationError("Invalid review trigger")
        request = "auto"
        if trigger == "manual":
            note = positive(required(env, "REVIEW_NOTE_ID"))
            authorize_note(api, snapshot, note, bot)
            request = f"note-{note}"
        summary.update({"base": snapshot.base, "head": snapshot.head, "request": request})
        publish_setting = env.get("REVIEW_PUBLISH_COMMENT", "true")
        if publish_setting not in ("true", "false"):
            raise IntegrationError("REVIEW_PUBLISH_COMMENT must be true or false")
        publish = publish_setting == "true"
        if publish:
            publisher = Publisher(api, snapshot, bot)
            if publisher.completed(request):
                raise Skip("This request and snapshot already have a completed bot report")
        job_url = api.trusted_url(env["CI_JOB_URL"]) if env.get("CI_JOB_URL") else ""
        with tempfile.TemporaryDirectory(prefix="repo-aware-gitlab-") as temporary:
            root = Path(temporary)
            print(f"Fetching MR !{iid}: base={snapshot.base}, head={snapshot.head} (no checkout).", flush=True)
            repo = fetcher(api, snapshot, root, env)
            jar = Path(env.get("REVIEW_AGENT_JAR", "/opt/repo-aware/build/code-review-agent.jar")).resolve()
            if not jar.is_file():
                raise IntegrationError("Build the CLI JAR or set REVIEW_AGENT_JAR")
            run_root = root / "runs"
            args = ["java", "-jar", str(jar), "review", "--repo", str(repo), "--branch", snapshot.head, "--base", snapshot.base,
                    "--language", language, "--output-dir", str(run_root), "--review-parallelism", env.get("REVIEW_PARALLELISM", "1")]
            for prefix, option, filename in (("REVIEW_KNOWLEDGE", "--knowledge", "knowledge.md"),
                                              ("REVIEW_REQUIREMENT", "--requirement", "requirement.md")):
                local_file, tracked_path = env.get(prefix + "_FILE"), env.get(prefix + "_PATH")
                if local_file and tracked_path:
                    raise IntegrationError("Choose either a mounted document or a baseline repository path")
                document = None
                if local_file:
                    source = Path(local_file)
                    if not source.is_file() or source.stat().st_size > 4 * 1024 * 1024:
                        raise IntegrationError("Mounted business document must be a regular file of at most 4 MiB")
                    content = source.read_bytes()
                    if len(content) > 4 * 1024 * 1024:
                        raise IntegrationError("Mounted business document exceeds 4 MiB")
                    content.decode("utf-8", errors="strict")
                    document = root / filename
                    document.write_bytes(content)
                elif tracked_path:
                    document = baseline_file(repo, snapshot.base, tracked_path, root / filename)
                if document:
                    args += [option, str(document)]
                    if option == "--knowledge":
                        args += ["--knowledge-mode", "scoped"]
            for variable, option in (("REVIEW_AGENT_TIMEOUT_SECONDS", "--agent-timeout-seconds"),
                                     ("REVIEW_AGENT_RETRIES", "--agent-retries"),
                                     ("REVIEW_MAX_EVIDENCE_BYTES", "--max-evidence-bytes")):
                if env.get(variable):
                    args += [option, env[variable]]
            # Separate arguments, no shell. No project-defined tools, checkout or commands are enabled.
            args += ["--agent-command", "claude", "--agent-arg", "-p", "--agent-arg", "--bare", "--agent-arg", "--tools", "--agent-arg", "",
                     "--agent-arg", "--strict-mcp-config", "--agent-arg", "--mcp-config", "--agent-arg", '{"mcpServers":{}}',
                     "--agent-arg", "--no-session-persistence"]
            if api.snapshot(project, iid) != snapshot:
                raise Skip("MR changed before analysis")
            if publisher:
                publisher.write("RUNNING", request, "正在静态审查。" if language == "zh-CN" else "Static review is running.", language, job_url)
            analysis_started = True
            print(f"Running static review; language={language}; parallelism={env.get('REVIEW_PARALLELISM', '1')}.", flush=True)
            with (output / "cli-stdout.log").open("wb") as stdout, (output / "cli-stderr.log").open("wb") as stderr:
                exit_code = runner(args, root, agent_environment(dict(env)), stdout, stderr, int(env.get("REVIEW_TIMEOUT_SECONDS", "5400")))
            directories = list(run_root.iterdir()) if run_root.exists() else []
            report = "未产出有效审查报告，请查看 CI 日志。" if language == "zh-CN" else "No validated report was produced. Inspect the CI job logs."
            status = "FAILED"
            if len(directories) == 1 and directories[0].is_dir():
                run = directories[0]
                for name in PUBLIC_FILES:
                    if (run / name).is_file():
                        shutil.copyfile(run / name, public / name)
                # These producer-owned files contain categories/positions, never raw model errors or quotes.
                # Preserve task-state paths because reports link to them relative to their run directory.
                for pattern, prefix in (("*-state.properties", public), ("*-execution.log", public / "diagnostics")):
                    for metadata in run.rglob(pattern):
                        if not metadata.is_file() or metadata.is_symlink():
                            continue
                        target = prefix / metadata.relative_to(run)
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(metadata, target)
                if (run / "report.md").is_file():
                    report = (run / "report.md").read_text(encoding="utf-8")
                if (run / "review-status.md").is_file():
                    match = re.search(r"(?m)^-?\s*Status: (COMPLETE|INCOMPLETE|FAILED|CANCELLED|SKIPPED)\s*$", (run / "review-status.md").read_text())
                    if match:
                        status = match[1]
            if exit_code not in (0, 3) or (exit_code == 0 and status not in ("COMPLETE", "SKIPPED")) or (exit_code == 3 and status != "INCOMPLETE"):
                status = "FAILED"
            summary.update({"status": status, "cli_status": status, "cli_exit_code": exit_code})
            print(f"CLI exit={exit_code}; review status={status}. Published artifacts exclude raw model prompts and streams.", flush=True)
            (public / "report.md").write_text(report, encoding="utf-8")
            if api.snapshot(project, iid) != snapshot:
                summary["status"] = "STALE"
                # Do not publish findings for an outdated snapshot. The artifact retains its explicit SHAs.
                if publisher:
                    publisher.write("STALE", request, "版本已变化，旧结果仅保留在 CI 产物中。" if language == "zh-CN" else "Base or head changed. Findings are retained only in CI artifacts.", language, job_url)
                return 0
            if publisher:
                publisher.write(status, request, report, language, job_url)
            return 0 if status in ("COMPLETE", "SKIPPED") else 3 if status == "INCOMPLETE" else 1
    except Skip as skipped:
        summary.update({"status": "STALE" if analysis_started else "SKIPPED", "reason": str(skipped)})
        if analysis_started and publisher:
            notice = "MR 已关闭或转为草稿，本次不回写问题。" if language == "zh-CN" else "MR is no longer eligible; no findings are published."
            publisher.write("STALE", request, notice, language, job_url)
        return 0
    except KeyboardInterrupt:
        summary["status"] = "CANCELLED"
        if analysis_started and publisher:
            try:
                publisher.write("CANCELLED", request, "审查已取消。" if language == "zh-CN" else "Review was cancelled.", language, job_url)
            except Exception:
                pass  # Cancellation must still stop the process and persist the local status.
        raise
    except Exception as error:
        summary["status"] = "FAILED"
        summary["error_type"] = type(error).__name__
        if analysis_started and publisher:
            try:
                publisher.write("FAILED", request, "审查执行失败，请查看 CI 产物。" if language == "zh-CN" else "Review execution failed; inspect CI artifacts.", language, job_url)
            except Exception:
                pass
        raise
    finally:
        (public / "integration-status.json").write_text(json.dumps(summary, indent=2) + "\n")


def main():
    os.umask(0o077)
    try:
        return review()
    except IntegrationError as error:
        print("GitLab review integration failed: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError) as error:
        print("GitLab review integration failed (" + type(error).__name__ + "); check CI configuration and artifacts.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    except Exception as error:
        print("GitLab review failed (" + type(error).__name__ + "); see integration artifacts.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
