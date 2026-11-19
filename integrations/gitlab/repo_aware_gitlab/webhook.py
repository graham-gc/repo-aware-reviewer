"""Authenticated, durable webhook-to-CI bridge. It never executes reviewed code or a model."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import signal
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote

from .api import ApiError, IntegrationError, Skip, api_from_env, authorize_note, positive, project_ids, required, sha
from .state import State


def normalize(event_type, payload):
    attrs = payload.get("object_attributes", {})
    project = positive(payload.get("project", {}).get("id", payload.get("project_id")))
    if event_type == "Merge Request Hook" and payload.get("object_kind") == "merge_request":
        action = attrs.get("action")
        changes = payload.get("changes", {})
        ready = any(changes.get(field, {}).get("previous") is True and changes.get(field, {}).get("current") is False
                    for field in ("draft", "work_in_progress"))
        title = changes.get("title", {})
        ready = ready or (str(title.get("previous", "")).lower().startswith(("draft:", "wip:"))
                          and not str(title.get("current", "")).lower().startswith(("draft:", "wip:")))
        if action not in ("open", "reopen", "update") or (action == "update" and not attrs.get("oldrev") and not ready and "target_branch" not in changes):
            raise Skip("No reviewable MR change")
        target = positive(attrs.get("target_project_id", project))
        source = positive(attrs.get("source_project_id", project))
        if source != project or target != project:
            raise Skip("Fork MR event")
        head = attrs.get("last_commit", {}).get("id")
        return {"kind": "auto", "project": project, "iid": positive(attrs.get("iid")), "head": sha(head) if head else "",
                "change": hashlib.sha256(json.dumps([action, attrs.get("updated_at"), attrs.get("oldrev")]).encode()).hexdigest()}
    if event_type == "Note Hook" and payload.get("object_kind") == "note":
        if attrs.get("action", "create") != "create" or attrs.get("noteable_type") != "MergeRequest" or attrs.get("note", "").strip() != "/review":
            raise Skip("Not a new MR /review command")
        return {"kind": "manual", "project": project, "iid": positive(payload.get("merge_request", {}).get("iid")),
                "note": positive(attrs.get("id")), "actor": positive(payload.get("user", {}).get("id"))}
    raise Skip("Unsupported webhook event")


class Bridge:
    def __init__(self, api, state, allowed, pipeline_project, pipeline_ref, trigger_token, bot_id):
        self.api, self.state, self.allowed = api, state, allowed
        self.pipeline_project, self.pipeline_ref, self.trigger_token, self.bot_id = pipeline_project, pipeline_ref, trigger_token, bot_id
        self.stop = threading.Event()

    def process(self, event):
        if event["project"] not in self.allowed:
            raise Skip("Project is not allowlisted")
        snapshot = self.api.snapshot(event["project"], event["iid"])
        if event["kind"] == "manual":
            authorize_note(self.api, snapshot, event["note"], self.bot_id, event["actor"])
            key = f"manual:{snapshot.project}:{snapshot.iid}:{event['note']}"
        else:
            if event.get("head") and event["head"] != snapshot.head:
                raise Skip("Event was superseded by a newer head")
            key = "auto:" + snapshot.key()
        if not self.state.claim(key):
            return "duplicate"
        variables = {"REVIEW_PROJECT_ID": str(snapshot.project), "REVIEW_MR_IID": str(snapshot.iid),
                     "REVIEW_EXPECTED_BASE": snapshot.base, "REVIEW_EXPECTED_HEAD": snapshot.head,
                     "REVIEW_TRIGGER": event["kind"], "REVIEW_REQUEST_ID": hashlib.sha256(key.encode()).hexdigest()}
        if event["kind"] == "manual":
            variables["REVIEW_NOTE_ID"] = str(event["note"])
        try:
            result = self.api.trigger(self.pipeline_project, self.pipeline_ref, self.trigger_token, variables)
            pipeline = positive(result.get("id"))
            self.state.triggered(key, pipeline)
        except ApiError as error:
            self.state.triggered(key, status="UNKNOWN" if error.uncertain else "FAILED")
            raise
        except Exception:
            self.state.triggered(key, status="UNKNOWN")
            raise ApiError("POST trigger result", uncertain=True) from None
        return f"pipeline:{pipeline}"

    def once(self):
        item = self.state.next()
        if not item:
            return False
        key, event, attempt = item
        try:
            detail = self.process(event)
            self.state.finish(key, "DONE", detail)
            print(json.dumps({"event": key, "state": "DONE", "detail": detail}), flush=True)
        except Skip as skipped:
            self.state.finish(key, "SKIPPED", str(skipped))
        except ApiError as error:
            retry = error.retryable and attempt < 3
            status = "RECEIVED" if retry else "UNKNOWN" if error.uncertain else "FAILED"
            self.state.finish(key, status, str(error), delay=5 * attempt)
            print(json.dumps({"event": key, "state": status, "reason": str(error)}), flush=True)
        except Exception as error:
            # Only the exception class is logged; third-party errors may contain request credentials.
            self.state.finish(key, "FAILED", type(error).__name__)
            print(json.dumps({"event": key, "state": "FAILED", "reason": type(error).__name__}), flush=True)
        return True

    def work(self):
        while not self.stop.is_set():
            try:
                if not self.once():
                    self.stop.wait(0.5)
            except Exception as error:
                print(json.dumps({"state": "INBOX_ERROR", "reason": type(error).__name__}), flush=True)
                self.stop.wait(2)


def server(bridge, secret, address):
    if len(secret) < 24:
        raise IntegrationError("GITLAB_WEBHOOK_SECRET must contain at least 24 characters")

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *args):
            pass  # Do not log arbitrary paths, payloads or request headers.

        def respond(self, code, value):
            body = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != "/healthz":
                return self.respond(404, {"status": "not found"})
            try:
                with bridge.state.connect() as db:
                    db.execute("SELECT 1").fetchone()
                ready = not bridge.stop.is_set()
                self.respond(200 if ready else 503, {"status": "ok" if ready else "stopping"})
            except Exception:
                self.respond(503, {"status": "inbox unavailable"})

        def do_POST(self):
            if self.path != "/webhook":
                return self.respond(404, {"error": "not found"})
            supplied = self.headers.get("X-Gitlab-Token", "")
            if not hmac.compare_digest(supplied.encode(), secret.encode()):
                return self.respond(401, {"error": "unauthorized"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 1024 * 1024:
                    return self.respond(413, {"error": "body limit"})
                raw = self.rfile.read(length)
                if len(raw) != length:
                    return self.respond(400, {"error": "incomplete body"})
                event = normalize(self.headers.get("X-Gitlab-Event"), json.loads(raw))
                if event["project"] not in bridge.allowed:
                    return self.respond(403, {"error": "project not allowed"})
                delivery = self.headers.get("webhook-id") or self.headers.get("Idempotency-Key")
                if delivery and (len(delivery) > 128 or any(ord(c) < 33 or ord(c) > 126 for c in delivery)):
                    return self.respond(400, {"error": "invalid delivery identifier"})
                normalized = json.dumps(event, sort_keys=True)
                key = hashlib.sha256((str(event["project"]) + ":" + (delivery or normalized)).encode()).hexdigest()
                inserted = bridge.state.enqueue(key, event)
                self.respond(202, {"accepted": inserted})
            except Skip:
                self.respond(200, {"skipped": True})
            except (ValueError, TypeError, KeyError, AttributeError, IntegrationError):
                self.respond(400, {"error": "invalid event"})
            except Exception:
                self.respond(503, {"error": "inbox unavailable"})

    class Server(ThreadingHTTPServer):
        daemon_threads = True

        def __init__(self, *args):
            self.slots = threading.BoundedSemaphore(32)
            super().__init__(*args)

        def process_request(self, request, client_address):
            if not self.slots.acquire(blocking=False):
                self.shutdown_request(request)
                return
            try:
                super().process_request(request, client_address)
            except Exception:
                self.slots.release()
                raise

        def process_request_thread(self, request, client_address):
            try:
                super().process_request_thread(request, client_address)
            finally:
                self.slots.release()

    return Server(address, Handler)


def main():
    os.umask(0o077)
    try:
        api = api_from_env(read_only=True)
        allowed = project_ids(required(os.environ, "GITLAB_REVIEW_PROJECT_IDS"))
        pipeline_project = positive(required(os.environ, "GITLAB_PIPELINE_PROJECT_ID"))
        pipeline_ref = required(os.environ, "GITLAB_PIPELINE_REF")
        branch = api.get(f"/projects/{pipeline_project}/repository/branches/{quote(pipeline_ref, safe='')}")
        if not branch.get("protected"):
            raise IntegrationError("The review pipeline must run from a protected trusted branch")
        bot = positive(os.environ["GITLAB_REVIEW_BOT_USER_ID"]) if os.environ.get("GITLAB_REVIEW_BOT_USER_ID") else api.identity()
        state = State(os.environ.get("GITLAB_STATE_DB", "/data/gitlab-events.sqlite3"))
        state.recover()
        bridge = Bridge(api, state, allowed, pipeline_project, pipeline_ref, required(os.environ, "GITLAB_TRIGGER_TOKEN"), bot)
        http = server(bridge, required(os.environ, "GITLAB_WEBHOOK_SECRET"), (os.environ.get("LISTEN_HOST", "0.0.0.0"), int(os.environ.get("PORT", "8080"))))
        worker = threading.Thread(target=bridge.work, daemon=True)
        worker.start()
        def shutdown(*_):
            bridge.stop.set()
            threading.Thread(target=http.shutdown, daemon=True).start()
        signal.signal(signal.SIGTERM, shutdown)
        signal.signal(signal.SIGINT, shutdown)
        try:
            http.serve_forever()
        finally:
            http.server_close()
            bridge.stop.set()
            worker.join(timeout=20)
    except IntegrationError as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
