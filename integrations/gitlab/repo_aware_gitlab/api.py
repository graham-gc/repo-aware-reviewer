from __future__ import annotations

import json
import os
import re
import ssl
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener


class IntegrationError(RuntimeError):
    pass


class Skip(IntegrationError):
    """An event or snapshot is deliberately outside the review scope."""


class ApiError(IntegrationError):
    def __init__(self, operation: str, status: int | None = None, *, retryable=False, uncertain=False):
        super().__init__(f"GitLab {operation} failed (HTTP {status if status else 'unavailable'})")
        self.status, self.retryable, self.uncertain = status, retryable, uncertain


def required(env, name):
    value = env.get(name, "").strip()
    if not value:
        raise IntegrationError(f"Missing {name}")
    return value


def positive(value):
    if isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]*", str(value)):
        raise IntegrationError("Expected a positive GitLab identifier")
    return int(value)


def project_ids(value):
    result = frozenset(positive(item.strip()) for item in value.split(",") if item.strip())
    if not result:
        raise IntegrationError("GITLAB_REVIEW_PROJECT_IDS must list allowed project IDs")
    return result


def sha(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise IntegrationError("Missing or invalid GitLab commit SHA")
    return value


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None  # Never forward credentials to a redirected destination.


@dataclass(frozen=True)
class Snapshot:
    project: int
    iid: int
    base: str
    head: str
    target_branch: str
    source_branch: str

    def key(self):
        return f"{self.project}:{self.iid}:{self.base}:{self.head}"


class GitLab:
    def __init__(self, url, token, *, timeout=15, ca_file=None, allow_http=False):
        self.url = url.rstrip("/")
        parsed = urlsplit(self.url)
        local_http = allow_http and parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost", "::1")
        if (parsed.scheme != "https" and not local_http) or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise IntegrationError("GITLAB_URL must be a credential-free HTTPS URL (HTTP is limited to explicit localhost tests)")
        self.origin = (parsed.scheme, parsed.netloc)
        self.token, self.timeout = token, timeout
        self.opener = build_opener(NoRedirect(), HTTPSHandler(context=ssl.create_default_context(cafile=ca_file)))

    def trusted_url(self, url):
        parsed = urlsplit(url)
        if (parsed.scheme, parsed.netloc) != self.origin or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise IntegrationError("GitLab returned a URL outside the configured origin")
        if not parsed.path.startswith(urlsplit(self.url).path.rstrip("/") + "/"):
            raise IntegrationError("GitLab URL is outside the configured installation")
        return url

    def request(self, method, path, data=None, *, form=False, auth=True):
        if not path.startswith("/") or path.startswith("//"):
            raise IntegrationError("Invalid API path")
        headers = {"Accept": "application/json", "User-Agent": "repo-aware-reviewer-gitlab"}
        if auth:
            headers["PRIVATE-TOKEN"] = self.token
        body = None
        if data is not None:
            body = (urlencode(data) if form else json.dumps(data)).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        request = Request(self.url + "/api/v4" + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read(16 * 1024 * 1024 + 1)
                if len(raw) > 16 * 1024 * 1024:
                    raise ApiError(method + " response-size-limit", uncertain=method != "GET")
                try:
                    return json.loads(raw), response.headers
                except (ValueError, UnicodeError):
                    raise ApiError(method + " invalid-json", uncertain=method != "GET") from None
        except HTTPError as error:
            error.close()
            raise ApiError(method, error.code, retryable=method == "GET" and error.code in (429, 502, 503, 504),
                           uncertain=method != "GET" and error.code >= 500) from None
        except (URLError, TimeoutError, ConnectionError, OSError):
            raise ApiError(method, retryable=method == "GET", uncertain=method != "GET") from None

    def get(self, path):
        return self.request("GET", path)[0]

    def identity(self):
        return positive(self.get("/user")["id"])

    def snapshot(self, project, iid):
        project, iid = positive(project), positive(iid)
        mr = self.get(f"/projects/{project}/merge_requests/{iid}")
        if positive(mr.get("target_project_id")) != project or positive(mr.get("source_project_id")) != project:
            raise Skip("Fork merge requests are not supported in this first integration")
        if mr.get("state") != "opened" or mr.get("draft", mr.get("work_in_progress", False)):
            raise Skip("Merge request is closed or draft")
        target, source = mr.get("target_branch"), mr.get("source_branch")
        if not isinstance(target, str) or not target or not isinstance(source, str) or not source:
            raise IntegrationError("GitLab returned incomplete branch metadata")
        branch = self.get(f"/projects/{project}/repository/branches/{quote(target, safe='')}")
        if not mr.get("sha"):
            raise ApiError("MR snapshot not ready", retryable=True)
        return Snapshot(project, iid, sha(branch.get("commit", {}).get("id")), sha(mr.get("sha")), target, source)

    def notes(self, snapshot):
        path = f"/projects/{snapshot.project}/merge_requests/{snapshot.iid}/notes"
        page = "1"
        for _ in range(100):
            notes, headers = self.request("GET", path + "?" + urlencode({"per_page": 100, "page": page, "sort": "desc", "order_by": "updated_at"}))
            if not isinstance(notes, list):
                raise IntegrationError("Invalid GitLab notes response")
            yield from notes
            page = headers.get("X-Next-Page", "")
            if not page:
                return
            positive(page)
        raise IntegrationError("Too many notes to locate a previous bot report safely")

    def trigger(self, pipeline_project, ref, trigger_token, variables):
        fields = {"token": trigger_token, "ref": ref}
        fields.update({f"variables[{key}]": value for key, value in variables.items()})
        return self.request("POST", f"/projects/{positive(pipeline_project)}/trigger/pipeline", fields, form=True, auth=False)[0]


def api_from_env(env=os.environ, *, read_only=False):
    token = env.get("GITLAB_READ_TOKEN") if read_only else None
    return GitLab(required(env, "GITLAB_URL"), token or required(env, "GITLAB_TOKEN"), ca_file=env.get("GITLAB_CA_FILE"),
                  allow_http=env.get("GITLAB_ALLOW_LOCAL_HTTP") == "true")


def authorize_note(api, snapshot, note_id, bot_id, actor=None):
    note = api.get(f"/projects/{snapshot.project}/merge_requests/{snapshot.iid}/notes/{positive(note_id)}")
    author = positive(note.get("author", {}).get("id"))
    if author == bot_id or (actor is not None and author != actor) or note.get("system") or note.get("body", "").strip() != "/review":
        raise Skip("Not an eligible human /review command")
    try:
        member = api.get(f"/projects/{snapshot.project}/members/all/{author}")
    except ApiError as error:
        if error.status in (403, 404):
            raise Skip("Commenter does not have review permission") from None
        raise
    if member.get("id") != author or member.get("access_level", 0) < 30 or member.get("state", "active") != "active":
        raise Skip("Commenter must be an active Developer or higher")
    return author
