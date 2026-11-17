import base64
import concurrent.futures
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.parse import parse_qs, unquote, urlsplit
from urllib.request import Request, urlopen

from repo_aware_gitlab.api import ApiError, GitLab, IntegrationError, Skip
from repo_aware_gitlab.review import agent_environment, baseline_file, execute_cli, fetch_snapshot, review
from repo_aware_gitlab.state import State
from repo_aware_gitlab.webhook import Bridge, normalize, server

TOKEN = "api-test-credential"
SECRET = "webhook-test-secret-with-enough-length"
A, B, C = "a" * 40, "b" * 40, "c" * 40
ROOT = Path(__file__).resolve().parents[3]


class GitLabFixture:
    def __init__(self, root):
        self.root = root
        self.base, self.head = A, B
        self.draft, self.mr_state, self.source_project = False, "opened", 7
        self.notes, self.requests, self.triggers = [], [], []
        self.trigger_failure = False
        self.notes_failure = False
        fixture = self

        class Handler(SimpleHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=str(root), **kwargs)

            def log_message(self, *args):
                pass

            def answer(self, value, code=200, **headers):
                raw = json.dumps(value).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                for name, value in headers.items():
                    self.send_header(name, value)
                self.end_headers()
                self.wfile.write(raw)

            def route(self, method):
                path = unquote(urlsplit(self.path).path)
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                data = parse_qs(raw.decode()) if "x-www-form-urlencoded" in self.headers.get("Content-Type", "") else json.loads(raw or b"{}")
                fixture.requests.append((method, self.path, dict(self.headers), data))
                if path == "/api/v4/redirect":
                    self.send_response(302)
                    self.send_header("Location", fixture.url + "/must-not-follow")
                    self.end_headers()
                    return
                if path == "/api/v4/projects/99/trigger/pipeline":
                    if fixture.trigger_failure:
                        return self.answer({}, 503)
                    fixture.triggers.append(data)
                    return self.answer({"id": 201 + len(fixture.triggers)})
                if self.headers.get("PRIVATE-TOKEN") != TOKEN:
                    return self.answer({}, 401)
                if path == "/api/v4/user":
                    return self.answer({"id": 9})
                if path == "/api/v4/projects/7":
                    return self.answer({"id": 7, "http_url_to_repo": fixture.url + "/project.git"})
                if path == "/api/v4/projects/7/merge_requests/4":
                    return self.answer({"iid": 4, "state": fixture.mr_state, "draft": fixture.draft,
                                        "source_project_id": fixture.source_project, "target_project_id": 7,
                                        "target_branch": "main", "source_branch": "feature", "sha": fixture.head,
                                        "diff_refs": {"base_sha": C, "head_sha": fixture.head}})
                if path.endswith("/repository/branches/main"):
                    return self.answer({"commit": {"id": fixture.base}, "protected": True})
                if path.startswith("/api/v4/projects/7/members/all/"):
                    user = int(path.rsplit("/", 1)[1])
                    return self.answer({"id": user, "access_level": 30 if user == 8 else 20, "state": "active"})
                notes_path = "/api/v4/projects/7/merge_requests/4/notes"
                if path == notes_path or path.startswith(notes_path + "/"):
                    note_id = int(path.rsplit("/", 1)[1]) if path != notes_path else None
                    if method == "GET":
                        if note_id is None:
                            return self.answer(fixture.notes)
                        note = next((n for n in fixture.notes if n["id"] == note_id), None)
                        return self.answer(note or {}, 200 if note else 404)
                    if fixture.notes_failure:
                        return self.answer({}, 403)
                    if method == "POST":
                        note = {"id": max([n["id"] for n in fixture.notes] + [0]) + 1, "author": {"id": 9}, "system": False}
                        fixture.notes.insert(0, note)
                    else:
                        note = next(n for n in fixture.notes if n["id"] == note_id)
                    note["body"] = data["body"]
                    return self.answer(note)
                self.answer({}, 404)

            def do_GET(self):
                if urlsplit(self.path).path.startswith("/api/v4/"):
                    self.route("GET")
                elif self.path.startswith("/project.git/"):
                    expected = "Basic " + base64.b64encode(("oauth2:" + TOKEN).encode()).decode()
                    if self.headers.get("Authorization") != expected:
                        self.answer({}, 401)
                    else:
                        super().do_GET()
                else:
                    self.answer({}, 404)

            def do_POST(self):
                self.route("POST")

            def do_PUT(self):
                self.route("PUT")

        self.http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:" + str(self.http.server_port)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.api = GitLab(self.url, TOKEN, allow_http=True)

    def close(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join()


class IntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gitlab-integration-test-")
        self.root = Path(self.temp.name)
        self.fixture = GitLabFixture(self.root)
        self.state = State(self.root / "events.sqlite3")
        self.bridge = Bridge(self.fixture.api, self.state, frozenset({7}), 99, "main", "trigger-test-credential", 9)

    def tearDown(self):
        self.fixture.close()
        self.temp.cleanup()

    def automatic(self, **changes):
        attrs = {"iid": 4, "source_project_id": 7, "target_project_id": 7, "action": "open", "last_commit": {"id": self.fixture.head}}
        attrs.update(changes)
        return {"object_kind": "merge_request", "project": {"id": 7}, "object_attributes": attrs}

    def note(self, actor=8, note_id=50, body="/review", **changes):
        attrs = {"id": note_id, "noteable_type": "MergeRequest", "action": "create", "note": body}
        attrs.update(changes)
        return {"object_kind": "note", "project_id": 7, "merge_request": {"iid": 4}, "user": {"id": actor}, "object_attributes": attrs}

    def add_command(self, actor=8, note_id=50):
        self.fixture.notes.append({"id": note_id, "author": {"id": actor}, "body": "/review", "system": False})

    def env(self):
        return {"GITLAB_URL": self.fixture.url, "GITLAB_TOKEN": TOKEN, "GITLAB_REVIEW_PROJECT_IDS": "7",
                "GITLAB_ALLOW_LOCAL_HTTP": "true", "REVIEW_PROJECT_ID": "7", "REVIEW_MR_IID": "4",
                "REVIEW_AGENT_JAR": str(ROOT / "build/code-review-agent.jar"), "REVIEW_OUTPUT_DIR": str(self.root / "output")}

    def fake_fetch(self, api, snapshot, root, env):
        repo = root / "repo"
        repo.mkdir()
        return repo

    def fake_runner(self, status="COMPLETE", code=0, after=None):
        def runner(args, cwd, env, stdout, stderr, timeout):
            self.assertNotIn("GITLAB_TOKEN", env)
            self.assertNotIn("CI_JOB_TOKEN", env)
            self.assertIn("--bare", args)
            self.assertEqual(args[args.index("--base") + 1], self.fixture.base)
            run = Path(args[args.index("--output-dir") + 1]) / "test-run"
            run.mkdir(parents=True)
            (run / "report.md").write_text("VALIDATED_TEST_REPORT")
            (run / "review-status.md").write_text("Status: " + status + "\n")
            (run / "timings.md").write_text("timing metadata")
            (run / "evidence.md").write_text("PRIVATE_PROMPT_MUST_NOT_BE_UPLOADED")
            if after:
                after(args)
            return code
        return runner

    def test_snapshot_uses_target_tip_not_merge_base(self):
        snapshot = self.fixture.api.snapshot(7, 4)
        self.assertEqual(snapshot.base, A)
        self.assertNotEqual(snapshot.base, C)
        self.assertEqual(snapshot.head, B)

    def test_automatic_and_manual_trigger_dedup(self):
        event = normalize("Merge Request Hook", self.automatic())
        self.assertIn("pipeline:", self.bridge.process(event))
        self.assertEqual(self.bridge.process(event), "duplicate")
        self.assertEqual(len(self.fixture.triggers), 1)
        self.add_command()
        manual = normalize("Note Hook", self.note())
        self.assertIn("pipeline:", self.bridge.process(manual))
        self.assertEqual(self.bridge.process(manual), "duplicate")
        sent = self.fixture.triggers[-1]
        self.assertEqual(sent["variables[REVIEW_EXPECTED_BASE]"], [A])
        self.assertEqual(sent["variables[REVIEW_EXPECTED_HEAD]"], [B])
        trigger_headers = next(r[2] for r in self.fixture.requests if "trigger/pipeline" in r[1])
        self.assertNotIn("private-token", {key.lower() for key in trigger_headers})
        self.assertNotIn("trigger-test-credential", " ".join(r[1] for r in self.fixture.requests))

    def test_permission_bot_fork_draft_and_stale_filters(self):
        for actor in (9, 10):
            self.add_command(actor, actor)
            with self.assertRaises(Skip):
                self.bridge.process(normalize("Note Hook", self.note(actor, actor)))
        with self.assertRaises(Skip):
            normalize("Note Hook", self.note(body="/review something"))
        with self.assertRaises(Skip):
            normalize("Note Hook", self.note(action="update"))
        with self.assertRaises(Skip):
            normalize("Merge Request Hook", self.automatic(action="update"))
        for field, value in (("source_project", 8), ("draft", True), ("mr_state", "closed")):
            original = getattr(self.fixture, field)
            setattr(self.fixture, field, value)
            with self.assertRaises(Skip):
                self.bridge.process({"kind": "auto", "project": 7, "iid": 4, "head": B})
            setattr(self.fixture, field, original)
        with self.assertRaises(Skip):
            self.bridge.process({"kind": "auto", "project": 7, "iid": 4, "head": C})
        self.assertEqual(self.fixture.triggers, [])

    def test_unknown_trigger_result_is_not_retried(self):
        self.fixture.trigger_failure = True
        event = normalize("Merge Request Hook", self.automatic())
        with self.assertRaises(ApiError) as failure:
            self.bridge.process(event)
        self.assertTrue(failure.exception.uncertain)
        self.fixture.trigger_failure = False
        self.assertEqual(self.bridge.process(event), "duplicate")
        self.assertEqual(self.fixture.triggers, [])
        with self.state.connect() as db:
            self.assertEqual(db.execute("SELECT state FROM reviews").fetchone()[0], "UNKNOWN")

    def test_persistent_atomic_claims_and_recovery(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            claims = list(pool.map(lambda _: self.state.claim("same-snapshot"), range(20)))
        self.assertEqual(sum(claims), 1)
        event = normalize("Merge Request Hook", self.automatic())
        self.assertTrue(self.state.enqueue("delivery", event))
        self.assertIsNotNone(self.state.next())
        reopened = State(self.state.path)
        reopened.recover()
        self.assertIsNotNone(reopened.next())
        self.assertFalse(reopened.claim("same-snapshot"))
        with self.state.connect() as db:
            saved = db.execute("SELECT payload FROM events").fetchone()[0]
        self.assertNotIn(SECRET, saved)

    def test_webhook_ack_is_durable_and_does_not_run_review(self):
        http = server(self.bridge, SECRET, ("127.0.0.1", 0))
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        url = f"http://127.0.0.1:{http.server_port}/webhook"
        def send(secret, payload, delivery="same-delivery"):
            request = Request(url, data=json.dumps(payload).encode(), headers={"X-Gitlab-Token": secret,
                              "X-Gitlab-Event": "Merge Request Hook", "Idempotency-Key": delivery})
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        try:
            with self.assertRaises(HTTPError) as unauthorized:
                send("incorrect", self.automatic())
            self.assertEqual(unauthorized.exception.code, 401)
            unauthorized.exception.close()
            self.assertEqual(send(SECRET, self.automatic()), (202, {"accepted": True}))
            self.assertEqual(send(SECRET, self.automatic()), (202, {"accepted": False}))
            self.assertEqual(self.fixture.triggers, [])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertTrue(self.bridge.once())
            self.assertEqual(len(self.fixture.triggers), 1)
        finally:
            http.shutdown(); http.server_close(); thread.join()

    def test_redirect_and_foreign_origin_do_not_receive_credentials(self):
        with self.assertRaises(ApiError) as error:
            self.fixture.api.get("/redirect")
        self.assertEqual(error.exception.status, 302)
        with self.assertRaises(IntegrationError):
            self.fixture.api.trusted_url("https://different.invalid/repo.git")
        with self.assertRaises(IntegrationError):
            self.fixture.api.trusted_url(self.fixture.url.replace("http://", "http://user:secret@") + "/repo.git")
        self.assertFalse(any("must-not-follow" in request[1] for request in self.fixture.requests))

    def test_review_updates_only_bot_report_and_publishes_incomplete(self):
        forged = {"id": 1, "author": {"id": 8}, "body": "<!-- repo-aware-reviewer:gitlab:7:4 -->\nforged"}
        self.fixture.notes.append(forged)
        env = self.env()
        env["CI_JOB_TOKEN"] = "job-test-credential"
        result = review(env, api=self.fixture.api, fetcher=self.fake_fetch, runner=self.fake_runner("INCOMPLETE", 3))
        self.assertEqual(result, 3)
        bot_notes = [n for n in self.fixture.notes if n["author"]["id"] == 9]
        self.assertEqual(len(bot_notes), 1)
        self.assertIn("INCOMPLETE", bot_notes[0]["body"])
        self.assertEqual(forged["body"], "<!-- repo-aware-reviewer:gitlab:7:4 -->\nforged")
        public = Path(env["REVIEW_OUTPUT_DIR"]) / "public"
        self.assertFalse((public / "evidence.md").exists())
        self.assertEqual(json.loads((public / "integration-status.json").read_text())["status"], "INCOMPLETE")
        self.assertEqual(review(env, api=self.fixture.api, fetcher=self.fake_fetch, runner=self.fake_runner()), 0)
        self.assertEqual(len([n for n in self.fixture.notes if n["author"]["id"] == 9]), 1)
        def forbidden(*args):
            raise AssertionError("completed snapshot must not invoke CLI again")
        self.assertEqual(review(env, api=self.fixture.api, fetcher=forbidden, runner=forbidden), 0)

    def test_mr_changes_during_review_withholds_findings(self):
        result = review(self.env(), api=self.fixture.api, fetcher=self.fake_fetch,
                        runner=self.fake_runner(after=lambda _: setattr(self.fixture, "head", C)))
        self.assertEqual(result, 0)
        self.assertIn("STALE", self.fixture.notes[0]["body"])
        self.assertNotIn("VALIDATED_TEST_REPORT", self.fixture.notes[0]["body"])

    def test_api_write_failure_stops_before_model_and_marks_failed(self):
        self.fixture.notes_failure = True
        def forbidden(*args):
            raise AssertionError("model must not run after write permission rejection")
        with self.assertRaises(ApiError):
            review(self.env(), api=self.fixture.api, fetcher=self.fake_fetch, runner=forbidden)
        status = self.root / "output/public/integration-status.json"
        self.assertEqual(json.loads(status.read_text())["status"], "FAILED")

    def test_mounted_business_documents_are_explicit_and_support_chinese(self):
        knowledge, requirement = self.root / "approved.md", self.root / "requirement.md"
        knowledge.write_text("# 主知识库\n人工批准的业务规则。\n", encoding="utf-8")
        requirement.write_text("# 需求\n失败时返回错误。\n", encoding="utf-8")
        env = self.env()
        env.update({"REVIEW_LANGUAGE": "zh-CN", "REVIEW_KNOWLEDGE_FILE": str(knowledge), "REVIEW_REQUIREMENT_FILE": str(requirement)})
        def check(args):
            self.assertEqual(Path(args[args.index("--knowledge") + 1]).read_bytes(), knowledge.read_bytes())
            self.assertEqual(Path(args[args.index("--requirement") + 1]).read_bytes(), requirement.read_bytes())
            self.assertEqual(args[args.index("--knowledge-mode") + 1], "scoped")
            self.assertEqual(args[args.index("--language") + 1], "zh-CN")
        self.assertEqual(review(env, api=self.fixture.api, fetcher=self.fake_fetch, runner=self.fake_runner(after=check)), 0)
        self.assertIn("代码审查", self.fixture.notes[0]["body"])

    def test_obsolete_pipeline_and_unauthorized_manual_request_skip_before_model(self):
        def forbidden(*args):
            raise AssertionError("ineligible requests must not fetch or run a model")
        env = self.env()
        env["REVIEW_EXPECTED_HEAD"] = C
        self.assertEqual(review(env, api=self.fixture.api, fetcher=forbidden, runner=forbidden), 0)
        env.pop("REVIEW_EXPECTED_HEAD")
        self.add_command(actor=10)
        env.update({"REVIEW_TRIGGER": "manual", "REVIEW_NOTE_ID": "50"})
        self.assertEqual(review(env, api=self.fixture.api, fetcher=forbidden, runner=forbidden), 0)
        self.assertEqual(len(self.fixture.notes), 1)

    def test_closed_mr_after_analysis_does_not_publish_findings(self):
        self.assertEqual(review(self.env(), api=self.fixture.api, fetcher=self.fake_fetch,
                                runner=self.fake_runner(after=lambda _: setattr(self.fixture, "mr_state", "closed"))), 0)
        self.assertIn("STALE", self.fixture.notes[0]["body"])
        self.assertNotIn("VALIDATED_TEST_REPORT", self.fixture.notes[0]["body"])

    def test_cli_timeout_and_failed_execution_are_reported(self):
        started = time.monotonic()
        with (self.root / "stdout").open("wb") as stdout, (self.root / "stderr").open("wb") as stderr:
            code = execute_cli([sys.executable, "-c", "import time; time.sleep(60)"], self.root, dict(os.environ), stdout, stderr, 0.1)
        self.assertEqual(code, 124)
        self.assertLess(time.monotonic() - started, 5)
        def failure(*args):
            raise OSError("untrusted failure details must not enter the comment")
        with self.assertRaises(OSError):
            review(self.env(), api=self.fixture.api, fetcher=self.fake_fetch, runner=failure)
        self.assertIn("FAILED", self.fixture.notes[0]["body"])
        self.assertNotIn("untrusted failure details", self.fixture.notes[0]["body"])

    def test_output_cleanup_refuses_symbolic_links_and_unowned_directories(self):
        destination = self.root / "preserved"
        destination.mkdir()
        sentinel = destination / "keep.txt"
        sentinel.write_text("existing user data")
        alias = self.root / "alias"
        alias.symlink_to(destination, target_is_directory=True)
        env = self.env()
        env["REVIEW_OUTPUT_DIR"] = str(alias)
        with self.assertRaises(IntegrationError):
            review(env, api=self.fixture.api)
        env["REVIEW_OUTPUT_DIR"] = str(self.root)
        (self.root / "public").mkdir()
        (self.root / "public/keep.txt").write_text("existing user data")
        with self.assertRaises(IntegrationError):
            review(env, api=self.fixture.api)
        self.assertEqual(sentinel.read_text(), "existing user data")
        self.assertTrue((self.root / "public/keep.txt").is_file())

    def test_model_environment_keeps_route_and_removes_gitlab_credentials(self):
        child = agent_environment({"GITLAB_TOKEN": TOKEN, "CI_JOB_TOKEN": TOKEN, "CI_REPOSITORY_URL": TOKEN,
                                   "GIT_CONFIG_VALUE_0": TOKEN, "ANTHROPIC_AUTH_TOKEN": "model-credential", "ANTHROPIC_BASE_URL": "https://gateway.invalid"})
        self.assertEqual(child, {"ANTHROPIC_AUTH_TOKEN": "model-credential", "ANTHROPIC_BASE_URL": "https://gateway.invalid"})

    def create_repository(self):
        work = self.root / "working"
        work.mkdir()
        def git(*args):
            return subprocess.check_output(["git", "-C", str(work), "-c", "user.name=Integration Test", "-c", "user.email=test@example.invalid", *args], stderr=subprocess.DEVNULL).decode().strip()
        git("init", "--quiet", "--template=")
        git("branch", "-M", "main")
        (work / "docs").mkdir()
        (work / "docs/knowledge.md").write_text("Approved baseline business knowledge.\n")
        (work / "docs/link.md").symlink_to("knowledge.md")
        (work / "runner.py").write_text("print('base')\n")
        git("add", "--all"); git("commit", "-qm", "base")
        self.fixture.base = git("rev-parse", "HEAD")
        (work / "runner.py").write_text("print('changed')\n")
        (work / "docs/knowledge.md").write_text("Unapproved head business rule.\n")
        (work / ".claude").mkdir()
        (work / ".claude/settings.json").write_text('{"hooks": {"untrusted": "must never run"}}')
        git("add", "--all"); git("commit", "-qm", "target")
        self.fixture.head = git("rev-parse", "HEAD")
        bare = self.root / "project.git"
        subprocess.run(["git", "clone", "--quiet", "--bare", str(work), str(bare)], check=True)
        for ref, sha in (("refs/heads/main", self.fixture.base), ("refs/merge-requests/4/head", self.fixture.head)):
            subprocess.run(["git", "-C", str(bare), "update-ref", ref, sha], check=True)
        subprocess.run(["git", "-C", str(bare), "update-server-info"], check=True)

    def test_real_git_fetch_has_empty_worktree_and_baseline_documents(self):
        self.create_repository()
        snapshot = self.fixture.api.snapshot(7, 4)
        temporary = self.root / "fetched"
        temporary.mkdir()
        repo = fetch_snapshot(self.fixture.api, snapshot, temporary, dict(os.environ))
        self.assertFalse((repo / "runner.py").exists())
        self.assertFalse((repo / ".claude").exists())
        document = baseline_file(repo, snapshot.base, "docs/knowledge.md", temporary / "knowledge.md")
        self.assertIn("Approved baseline", document.read_text())
        with self.assertRaises(IntegrationError):
            baseline_file(repo, snapshot.base, "docs/link.md", temporary / "link.md")

    @unittest.skipUnless((ROOT / "build/code-review-agent.jar").exists(), "Build CLI JAR before the packaged integration test")
    def test_real_jar_with_fake_claude_and_fake_gitlab(self):
        self.create_repository()
        self.bridge.process(normalize("Merge Request Hook", self.automatic()))
        binaries = self.root / "bin"
        binaries.mkdir()
        claude = binaries / "claude"
        claude.write_text('''#!/bin/sh
if [ -n "${GITLAB_TOKEN:-}${CI_JOB_TOKEN:-}${CI_REPOSITORY_URL:-}" ]; then exit 77; fi
prompt=$(cat)
case "$prompt" in
 'You are the discovery stage'*) printf '<findings/>';;
 'You are the report consolidation'*) cat <<'REPORT'
<finding-groups/>
# Review scope

# Functional and business change

# Findings

# Requirement assessment

# Knowledge-base feedback

# Verification limits
REPORT
 ;;
 *) exit 99;;
esac
''')
        claude.chmod(0o755)
        env = dict(os.environ)
        env.update(self.env())
        env.update({key[len("variables["):-1]: value[0] for key, value in self.fixture.triggers[0].items() if key.startswith("variables[")})
        env.update({"PATH": str(binaries) + os.pathsep + os.environ["PATH"], "CI_JOB_TOKEN": "job-test-credential",
                    "CI_REPOSITORY_URL": "https://user:job-test-credential@invalid/repo.git", "REVIEW_TIMEOUT_SECONDS": "30",
                    "REVIEW_KNOWLEDGE_PATH": "docs/knowledge.md"})
        self.assertEqual(review(env, api=self.fixture.api), 0)
        public = self.root / "output/public"
        self.assertIn("No verified findings", (public / "report.md").read_text())
        self.assertTrue((public / "timings.md").is_file())
        self.assertTrue(list((public / "diagnostics").rglob("*-execution.log")))
        self.assertEqual(len([n for n in self.fixture.notes if n["author"]["id"] == 9]), 1)


if __name__ == "__main__":
    unittest.main()
