#!/usr/bin/env bash
set -euo pipefail
umask 077

api() {
    gh api --hostname "${GITHUB_SERVER_URL#https://}" "$@"
}

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

eligible_pr() {
    jq -e --arg repository "$GITHUB_REPOSITORY" '
        .state == "open" and .draft == false and
        .base.repo.full_name == $repository and .head.repo.full_name == $repository
    ' >/dev/null
}

read_base_file() {
    local repo=$1 base=$2 path=$3 output=$4 entry
    entry=$(git -C "$repo" --literal-pathspecs ls-tree --full-tree \
        --format='%(objectmode) %(objecttype) %(objectname)' "$base" -- "$path")
    if [[ ! $entry =~ ^100(644|755)\ blob\ ([0-9a-f]{40})$ ]]; then
        fail "Expected a regular file at '$path' in the PR base commit $base"
    fi
    git -C "$repo" cat-file blob "${BASH_REMATCH[2]}" > "$output"
}

prepare() {
    local number event_head commenter permission pr base head root repo authorization
    event_head=''
    case "$GITHUB_EVENT_NAME" in
        pull_request_target)
            number=$(jq -er '.number' "$GITHUB_EVENT_PATH")
            event_head=$(jq -er '.pull_request.head.sha' "$GITHUB_EVENT_PATH")
            ;;
        issue_comment)
            if ! jq -e '
                .action == "created" and .issue.pull_request != null and
                .comment.body == "/review" and .comment.user.type == "User"
            ' "$GITHUB_EVENT_PATH" >/dev/null; then
                printf 'Skipping comment without a /review request on a pull request.\n'
                return
            fi
            number=$(jq -er '.issue.number' "$GITHUB_EVENT_PATH")
            commenter=$(jq -er '.comment.user.login' "$GITHUB_EVENT_PATH")
            [[ $commenter =~ ^[A-Za-z0-9_-]+$ ]] || fail 'Invalid GitHub login'
            permission=$(api "repos/$GITHUB_REPOSITORY/collaborators/$commenter/permission" --jq .permission)
            if [[ $permission != write && $permission != admin ]]; then
                printf 'Skipping /review because the commenter lacks repository write access.\n'
                return
            fi
            ;;
        *)
            fail 'Expected a pull_request_target or issue_comment event'
            ;;
    esac
    [[ $number =~ ^[1-9][0-9]*$ ]] || fail 'Invalid pull request number'
    pr=$(api "repos/$GITHUB_REPOSITORY/pulls/$number")
    if ! eligible_pr <<< "$pr"; then
        printf 'Skipping pull request outside the open, ready, same-repository scope.\n'
        return
    fi
    base=$(jq -er '.base.sha' <<< "$pr")
    head=$(jq -er '.head.sha' <<< "$pr")
    [[ $base =~ ^[0-9a-f]{40}$ && $head =~ ^[0-9a-f]{40}$ ]] || fail 'Invalid pull request commit SHA'
    if [[ -n $event_head && $event_head != "$head" ]]; then
        printf 'Skipping a pull request event superseded by a newer head commit.\n'
        return
    fi
    root=$(mktemp -d "$RUNNER_TEMP/repo-aware-review.XXXXXXXX")
    repo="$root/repo"

    # The CLI reads Git objects, so the worktree can stay empty.
    git init --quiet --template='' "$repo"
    authorization=$(printf 'x-access-token:%s' "${GH_TOKEN:?Missing GitHub token}" | base64 | tr -d '\r\n')
    GIT_TERMINAL_PROMPT=0 GIT_CONFIG_COUNT=1 \
        GIT_CONFIG_KEY_0="http.$GITHUB_SERVER_URL/.extraheader" \
        GIT_CONFIG_VALUE_0="AUTHORIZATION: basic $authorization" \
        git -C "$repo" fetch --quiet --no-tags "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY.git" "$base" "$head"

    if [[ -n ${REVIEW_KNOWLEDGE_PATH:-} ]]; then
        read_base_file "$repo" "$base" "$REVIEW_KNOWLEDGE_PATH" "$root/knowledge.md"
    fi
    if [[ -n ${REVIEW_REQUIREMENT_PATH:-} ]]; then
        read_base_file "$repo" "$base" "$REVIEW_REQUIREMENT_PATH" "$root/requirement.md"
    fi
    printf 'root=%s\nnumber=%s\nbase=%s\nhead=%s\n' "$root" "$number" "$base" "$head" >> "$GITHUB_OUTPUT"
}

review() {
    local jar directory
    local -a arguments=(review --repo "$REVIEW_ROOT/repo" --branch "$REVIEW_HEAD" --base "$REVIEW_BASE"
        --output-dir "$REVIEW_ROOT/output" --knowledge-mode "$REVIEW_KNOWLEDGE_MODE"
        --agent-command claude --agent-arg -p --agent-arg --bare --agent-arg --tools --agent-arg ''
        --agent-arg --strict-mcp-config --agent-arg --mcp-config --agent-arg '{"mcpServers":{}}'
        --agent-arg --no-session-persistence)
    unset GH_TOKEN GH_ENTERPRISE_TOKEN GITHUB_TOKEN
    command -v claude >/dev/null || fail 'Claude Code must be installed on the runner'
    if [[ -f $REVIEW_ROOT/knowledge.md ]]; then
        arguments+=(--knowledge "$REVIEW_ROOT/knowledge.md")
    fi
    if [[ -f $REVIEW_ROOT/requirement.md ]]; then
        arguments+=(--requirement "$REVIEW_ROOT/requirement.md")
    fi
    jar=$(bash "$GITHUB_ACTION_PATH/scripts/build.sh")
    directory=$(java -jar "$jar" "${arguments[@]}")
    printf 'directory=%s\n' "$directory" >> "$GITHUB_OUTPUT"
}

publish() {
    local pr report run_url
    pr=$(api "repos/$GITHUB_REPOSITORY/pulls/$REVIEW_NUMBER")
    if ! eligible_pr <<< "$pr" || ! jq -e --arg base "$REVIEW_BASE" --arg head "$REVIEW_HEAD" \
        '.base.sha == $base and .head.sha == $head' <<< "$pr" >/dev/null; then
        printf 'Pull request changed during review. The report is available in the workflow artifact.\n'
        return
    fi

    report="$REVIEW_DIRECTORY/report.md"
    run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
    {
        printf '## Repo-aware review\n\nPR base `%s` · head `%s`\n\n' "$REVIEW_BASE" "$REVIEW_HEAD"
        printf '[Review report and coverage artifact](%s)\n\n' "$run_url"
        if (( $(wc -c < "$report") <= 60000 )); then
            cat "$report"
        else
            printf 'The full report exceeds the comment size limit. Download it from the workflow run.\n'
        fi
    } > "$REVIEW_ROOT/comment.md"
    jq -n --rawfile body "$REVIEW_ROOT/comment.md" '{body: $body}' > "$REVIEW_ROOT/comment.json"
    api --method POST "repos/$GITHUB_REPOSITORY/issues/$REVIEW_NUMBER/comments" \
        --input "$REVIEW_ROOT/comment.json" --jq .html_url
}

case "${1:-}" in
    prepare) prepare ;;
    review) review ;;
    publish) publish ;;
    *) fail 'Expected prepare, review, or publish' ;;
esac
