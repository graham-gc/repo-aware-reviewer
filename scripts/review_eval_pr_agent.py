"""Run the PR-Agent plain-diff CLI on pinned Git input."""

import json
import os
from pathlib import Path
import re
import subprocess
import time


RUN_DETAILS = re.compile(
    r"(?ms)^<details>\s*<summary>[^\n]*Agent run details[^\n]*</summary>\s*\n.*?^</details>[ \t]*\n?"
)


def run_git(case, *args, stdout=subprocess.PIPE):
    return subprocess.run(["git", "-C", str(case["repo"]), *args], stdout=stdout,
                          stderr=subprocess.PIPE, check=True).stdout


def stage_sources(case, checkout, diff):
    options = ("diff", "--no-color", "--no-relative", "--no-ext-diff", "--no-textconv",
               "--find-renames", "--ignore-submodules=none", "--submodule=short")
    revisions = (case["base"], case["head"], "--")
    with diff.open("wb") as handle:
        run_git(case, *options, "--src-prefix=a/", "--dst-prefix=b/", *revisions, stdout=handle)
    # Use blob IDs from the pinned head. Never check out a commit, run filters, or materialize symlinks.
    entries = iter(run_git(case, *options, "--raw", "-z", "--no-abbrev", *revisions).split(b"\0")[:-1])
    for descriptor in entries:
        _, mode, _, blob, status = descriptor.split()
        name = next(entries)
        if status.startswith((b"R", b"C")):
            name = next(entries)
        if mode not in (b"100644", b"100755"):
            continue
        parts = os.fsdecode(name).split("/")
        if any(part in ("", ".", "..", ".git") for part in parts):
            raise ValueError(f"Unsafe path in pinned Git input: {name!r}")
        path = checkout.joinpath(*parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as handle:
            run_git(case, "cat-file", "blob", blob.decode("ascii"), stdout=handle)


def read_result(markdown, structured, exit_code):
    if exit_code != 0 or not markdown.is_file() or not structured.is_file():
        return None, {"status": "FAILED"}
    try:
        payload = json.loads(structured.read_text(encoding="utf-8"))
        coverage = payload["coverage"]
        report = markdown.read_text(encoding="utf-8")
        omitted, clipped, failed = (coverage["files_not_reviewed"], coverage["files_partially_reviewed"],
                                    coverage.get("failed_chunks"))
        if (not report.strip() or not isinstance(payload["review"], dict)
                or not isinstance(omitted, list) or not isinstance(clipped, list)
                or (failed is not None and (type(failed) is not int or failed < 0))):
            return None, {"status": "FAILED"}
    except (OSError, ValueError, KeyError, TypeError):
        return None, {"status": "FAILED"}
    usage = payload.get("usage")
    metrics = {key: usage[key] for key in ("prompt_tokens", "completion_tokens", "total_tokens")
               if isinstance(usage, dict) and type(usage.get(key)) is int and usage[key] >= 0}
    return markdown, {"status": "INCOMPLETE" if omitted or clipped or failed else "COMPLETE",
                      "omitted_files": len(omitted), "clipped_files": len(clipped), "failed_chunks": failed, **metrics}


def run(case, executable, directory, language, documents):
    started = time.monotonic()
    directory.mkdir()
    checkout = directory / "sources"
    checkout.mkdir()
    diff = directory / "change.diff"
    markdown, structured = directory / "report.md", directory / "review.json"
    with (directory / "stdout.log").open("wb") as stdout, (directory / "stderr.log").open("wb") as stderr:
        try:
            stage_sources(case, checkout, diff)
            env = {**os.environ, "CONFIG__RESPONSE_LANGUAGE": "zh-CN" if language == "zh-CN" else "en-US",
                   "CONFIG__OUTPUT_RUN_DETAILS": "false"}
            if documents:
                env["PLAIN_DIFF__DESCRIPTION"] = "\n\n".join(
                    f"{kind.capitalize()} document\n\n{path.read_text(encoding='utf-8')}"
                    for kind, path in documents.items()
                )
            result = subprocess.run([executable, "--diff-file", str(diff), "--output", str(markdown),
                                     "--json-output", str(structured), "review"], cwd=checkout, env=env,
                                    stdout=stdout, stderr=stderr)
            exit_code = result.returncode
        except (OSError, ValueError, subprocess.CalledProcessError) as error:
            stderr.write(f"PR-Agent input or invocation failed: {error}\n".encode("utf-8", errors="replace"))
            exit_code = 2
    report, metrics = read_result(markdown, structured, exit_code)
    return report, {"exit": exit_code, "elapsed_seconds": round(time.monotonic() - started, 3),
                    "model_seconds": None, "model_attempts": None, **metrics}


def copy_for_grading(source, destination):
    # Keep the original report in the private directory and remove tool-specific run metadata.
    destination.write_text(RUN_DETAILS.sub("", source.read_text(encoding="utf-8")), encoding="utf-8")
