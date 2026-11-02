#!/usr/bin/env bash
set -euo pipefail

# Works out which PR the fleet should review, validates it and checks whether
# the fleet already reviewed exactly this head recently. Every fleet comment
# carries the marker `<!-- review-fleet:v1 sha=... ts=... pr=... -->` — it is
# the freshness gate's key. Outputs: pr, sha, skip, reason.
#
# Selection without an explicit number (no base filter — why there is none is
# at the comment "No base skip" below):
# - schedule: the FIRST open non-draft PR (most recently updated first) whose
#   head LACKS a fresh marker AND whose CI has settled (ci_settled). "Most
#   recently updated, full stop" would be wrong for a polling run: if the
#   newest PR is already reviewed the freshness gate skips the run, and no
#   other PR is ever reviewed. The marker is therefore both gate and queue.
#   With no candidate the run is skipped in green (reason=nothing-to-review).
# - workflow_dispatch without pr: the most recently updated open non-draft
#   PR. A human runs this, and "the current one" is the right guess. The CI
#   gate does not apply to manual runs: a human who asks for a review should
#   get one, red CI or not.
#
# Expects in the environment: REPO, FRESH_HOURS, CI_WAIT_HOURS, INPUT_PR,
# GITHUB_EVENT_NAME, FORCE and GH_TOKEN. Reads and writes nothing outside the
# checkout (except the outputs file the runner gives us). Runs before the
# Workspace step, so $FLEET_TMP does not exist here — and is not needed.

PR="${INPUT_PR:-}"

# Freshness: does the PR carry a full-run marker for exactly this head,
# younger than FRESH_HOURS? (exit 0 = fresh; LAST_FRESH_AGE is set to the age
# in seconds when the answer is yes.) Comments that carry the partial banner
# do not count, marker or not: older runs stamped the marker even when lenses
# were missing, and they must not lock a PR for a day on half a review. The
# whole check also errs on the safe side: a FULL run whose report happens to
# quote the banner text only loses its freshness skip — a rerun, never a
# missed review.
head_is_fresh() {
  local PR="$1" SHA="$2" LAST_BODY LAST_SHA LAST_TS
  LAST_BODY="$(gh pr view "$PR" -R "$REPO" --json comments --jq \
    '.comments | map(select((.author.login == "github-actions[bot]" or .author.login == "github-actions") and (.body | contains("review-fleet:v1")) and ((.body | contains("Partial run")) | not))) | last | .body // empty' || true)"
  [ -n "$LAST_BODY" ] || return 1
  LAST_SHA="$(printf '%s' "$LAST_BODY" | grep -o 'sha=[0-9a-f]\{40\}' | head -1 | cut -d= -f2 || true)"
  LAST_TS="$(printf '%s' "$LAST_BODY" | grep -o 'ts=[0-9]\{1,\}' | head -1 | cut -d= -f2 || true)"
  if [ "${LAST_SHA:-}" != "$SHA" ] || [ -z "${LAST_TS:-}" ]; then
    return 1
  fi
  LAST_FRESH_AGE=$(( $(date +%s) - LAST_TS ))
  [ "$LAST_FRESH_AGE" -lt $(( FRESH_HOURS * 3600 )) ]
}

# Has CI settled on this head? (exit 0 = review now, 1 = wait; CI_VERDICT
# carries a readable line in both cases.) Green means every check on the head
# finished without failing — SKIPPED and NEUTRAL count as green, because a
# check that deliberately did not run has nothing to say.
#
# Why the gate exists: a review of a head CI has already rejected is five
# lenses spent on code that will change anyway, and it costs money. The
# repositories that run the fleet share ONE daily budget at OpenRouter, and on
# 2026-09-09 it ran out at 09:05 UTC — 21 real reviews that day against one
# or two the days before, mostly intermediate states of two PRs that were
# pushed again while being reviewed.
#
# The cap is the other half and it is not optional: without it the rule
# becomes a SILENT shutdown as soon as a check never turns green — a runner
# that is down, a job that hangs in the queue until GitHub fails it after a
# day, a red job nobody intends to fix. If nothing has happened on the head
# for CI_WAIT_HOURS it is reviewed anyway and the notice says why. The clock
# is the latest thing that happened on the head: a check that started, or —
# for a head without checks — the commit itself.
#
# CI_WAIT_HOURS=0 turns the wait off without an exception in the code (the
# age is always at least zero), and that is the answer for a repository
# without CI: there is nothing to wait for, and the gate would otherwise
# delay every review by the whole cap.
#
# **The gate only holds back on a definite answer.** If the rollup or the
# timestamp cannot be read the PR is reviewed as before — an unanswered API
# call must not silence the fleet, for the same reason the cap exists.
#
# A circle worth knowing about: when the fleet is dispatched with
# `--ref <branch>` its OWN check attaches to the branch's head, and a failed
# fleet run (budget gone, a lens that died) becomes a red cross the gate
# reads. The fleet can thus gate itself out of the PR it just ran on. It is
# not permanent — dispatch bypasses the gate and the cap releases after
# CI_WAIT_HOURS — and it is not worth a name filter that rots, but it explains
# a wait that otherwise looks incomprehensible.
ci_settled() {
  local PR="$1" SHA="$2" ROLLUP TOTAL FAILED_CHECKS PENDING_CHECKS LATEST WHEN WHEN_EPOCH NOW_EPOCH AGE
  # **Our own GraphQL query, not `gh pr view --json statusCheckRollup`.** The
  # latter decides for itself which subfields it fetches, and it takes
  # `checkSuite.workflowRun` — a field behind the `actions` scope. GraphQL
  # then answers with DATA PLUS ERRORS, gh returns exit≠0, and the gate saw an
  # empty rollup: "the checks could not be read", every run, while the checks
  # were there. Measured on another repository's poll at 15:50 UTC
  # 2026-09-09, which answered
  # `Resource not accessible by integration (… .checkSuite.workflowRun)`.
  # The query below asks for exactly the fields it uses, so `checks` and
  # `statuses` are enough and gh's field choice cannot make the gate useless
  # again.
  #
  # (.contexts.nodes // []) — the list is null, not empty, on a head without
  # checks, and `null[]` fails jq in the middle of the selection loop.
  ROLLUP="$(gh api graphql \
      -f owner="${REPO%%/*}" -f name="${REPO##*/}" -F nr="$PR" \
      -f query='query($owner: String!, $name: String!, $nr: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $nr) {
            commits(last: 1) { nodes { commit {
              statusCheckRollup { contexts(first: 100) { nodes {
                __typename
                ... on CheckRun { name status conclusion startedAt completedAt }
                ... on StatusContext { context state createdAt }
              } } }
            } } }
          }
        }
      }' --jq '
      [ ((.data.repository.pullRequest.commits.nodes[0].commit.statusCheckRollup.contexts.nodes) // [])[]
        | { n: ((.name // .context // "?")
               # The check name comes from the OWN workflow files of the PR
               # branch and is then written into a ::notice::. A newline in
               # a job name would let the branch write its own workflow
               # commands into the job log of the fleet — control characters
               # are flattened, the name is shortened.
               | gsub("[[:cntrl:]]"; " ") | .[0:60]),
            v: (.conclusion as $c
                | if .__typename == "StatusContext" then
                    (if .state == "SUCCESS" then "ok"
                     elif (.state == "PENDING" or .state == "EXPECTED") then "pending"
                     else "failed" end)
                  elif .status != "COMPLETED" then "pending"
                  elif ($c == "SUCCESS" or $c == "NEUTRAL" or $c == "SKIPPED") then "ok"
                  else "failed" end),
            t: (.startedAt // .completedAt // .createdAt // "") } ]
      | [ (length | tostring),
          ([ .[] | select(.v == "failed") | .n ] | join(", ")),
          ([ .[] | select(.v == "pending") | .n ] | join(", ")),
          ([ .[] | .t ] | max // "") ]
      | join("\u001f")' || true)"
  if [ -z "$ROLLUP" ]; then
    CI_VERDICT="the checks could not be read — reviewed as before"
    # A line of its own, and a warning: a notice is seen only by whoever opens
    # the log, and this is the state in which the gate does nothing at all.
    # To stderr, because in the selection loop stdout is the PR number.
    # (Found for real: without checks/statuses in permissions the rollup
    # answered empty, and the gate reviewed as before in every run.)
    echo "::warning::The fleet could not read the checks on PR #$PR — the CI gate did nothing." >&2
    return 0
  fi
  # \x1f, not tab: tab is an IFS WHITESPACE character, and read merges a run
  # of them into one separator — three empty columns then became none, and
  # the timestamp landed in "failed". The test caught it; a field that slides
  # one step to the left shows in nothing but the outcome.
  IFS=$'\x1f' read -r TOTAL FAILED_CHECKS PENDING_CHECKS LATEST <<< "$ROLLUP"
  if [ "$TOTAL" -gt 0 ] && [ -z "$FAILED_CHECKS" ] && [ -z "$PENDING_CHECKS" ]; then
    CI_VERDICT="CI green ($TOTAL checks)"
    return 0
  fi

  # The clock is GitHub's own timestamp on the checks when there are any:
  # they come from their clock, and a check that started is also the latest
  # thing that happened on the head. The committer date is the fallback for
  # a head WITHOUT checks — and it comes from a clock we do not own. A
  # computer whose clock is wrong dates commits ahead, and if that date were
  # the clock AGE would go negative: the cap, the only thing that keeps the
  # gate from becoming a silent shutdown, could not release until reality
  # had caught up with the skew. Hence both the order and the cap on
  # NOW_EPOCH.
  NOW_EPOCH="$(date +%s)"
  if [ -n "$LATEST" ]; then
    WHEN="$LATEST"
  else
    WHEN="$(gh api "repos/$REPO/commits/$SHA" --jq '.commit.committer.date' || true)"
  fi
  WHEN_EPOCH="$(date -d "$WHEN" +%s 2>/dev/null || echo 0)"
  if [ "$WHEN_EPOCH" = 0 ]; then
    CI_VERDICT="the head's age could not be read — reviewed as before"
    echo "::warning::The fleet could not read the age of the head of PR #$PR — the CI gate did nothing." >&2
    return 0
  fi
  if [ "$WHEN_EPOCH" -gt "$NOW_EPOCH" ]; then
    WHEN_EPOCH="$NOW_EPOCH"
  fi
  AGE=$(( NOW_EPOCH - WHEN_EPOCH ))

  if [ "$AGE" -ge $(( CI_WAIT_HOURS * 3600 )) ]; then
    CI_VERDICT="CI not green after $(( AGE / 3600 )) h (failed: ${FAILED_CHECKS:-none}; running: ${PENDING_CHECKS:-none}) — reviewed anyway"
    return 0
  fi
  # The age is in the waiting text, not only in the reviewed-anyway text: it
  # is the only thing that shows the clock is sane. A negative age here would
  # be a head dated in the future that pushes the cap ahead of itself.
  CI_VERDICT="waiting for CI for $(( AGE / 60 )) min (failed: ${FAILED_CHECKS:-none}; running: ${PENDING_CHECKS:-none}; $TOTAL checks)"
  return 1
}

if [ -z "$PR" ]; then
  if [ "${GITHUB_EVENT_NAME}" = "schedule" ]; then
    # Polling: the first candidate in update order without a fresh marker.
    # The notices about skipped candidates go to stderr, so they show in the
    # job log without ending up in the variable.
    PR="$(
      gh pr list -R "$REPO" --state open --limit 50 \
        --json number,headRefOid,isDraft,updatedAt \
        --jq '[.[] | select(.isDraft | not)] | sort_by(.updatedAt) | reverse | .[] | "\(.number) \(.headRefOid)"' \
        | while read -r NUM HEAD; do
            if head_is_fresh "$NUM" "$HEAD"; then
              printf '::notice::PR #%s has a fresh marker (%s) — next candidate.\n' \
                "$NUM" "${HEAD:0:12}" >&2
              continue
            fi
            if ! ci_settled "$NUM" "$HEAD"; then
              printf '::notice::PR #%s: %s — next candidate.\n' "$NUM" "$CI_VERDICT" >&2
              continue
            fi
            printf '%s\n' "$NUM"
            break
          done
    )"
  else
    # Manual run without a number: the most recently updated open non-draft
    # PR. Predictable enough to be practical, small enough that it cannot
    # surprise.
    PR="$(gh pr list -R "$REPO" --state open --limit 50 \
      --json number,isDraft,updatedAt \
      --jq '[.[] | select(.isDraft | not)] | sort_by(.updatedAt) | reverse | .[0].number // empty')"
  fi
fi

if [ -z "$PR" ]; then
  if [ "${GITHUB_EVENT_NAME}" = "schedule" ]; then
    echo "::notice::No open non-draft PR lacks a fresh marker — nothing to review."
    echo "skip=true" >> "$GITHUB_OUTPUT"
    echo "reason=nothing-to-review" >> "$GITHUB_OUTPUT"
    exit 0
  fi
  echo "::error::No PR to review. Give pr=<number> for a manual run."
  exit 1
fi

STATE="$(gh pr view "$PR" -R "$REPO" --json state --jq .state)"
SHA="$(gh pr view "$PR" -R "$REPO" --json headRefOid --jq .headRefOid)"

if [ "$STATE" != "OPEN" ]; then
  echo "::notice::PR #$PR is $STATE — the fleet skips it."
  echo "skip=true" >> "$GITHUB_OUTPUT"
  exit 0
fi

# No base skip here — and that is deliberate. The fleet first skipped every
# PR that did not target main: the selection filtered on --base main and a
# skip here caught the rest. That let stacked PRs (base = another feature
# branch) pass without ever being reviewed — a dispatch against a stacked PR
# ended on 2026-08-31 after 24 seconds with a notice, a green job and no
# comment on the PR. The restriction was removed because the diff against
# the PR's OWN base is the right one to review, and that is what
# `gh pr diff` already returns (fetch-diff.sh) — a stacked PR thus needs no
# special handling, neither here, in the selection above nor in the report's
# closing line. Do not put the filter back; if the selection is to be
# narrowed for some other reason, write why next to it.
echo "pr=$PR"   >> "$GITHUB_OUTPUT"
echo "sha=$SHA" >> "$GITHUB_OUTPUT"

if [ "${FORCE:-}" = "true" ]; then
  echo "skip=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

# The freshness gate: the same head reviewed within FRESH_HOURS → skip.
# Catches double triggers (two runs for the same PR almost at once — the
# schedule's selection applies the same rule through head_is_fresh, but can
# be overtaken by a parallel run between the selection and here) and reruns
# without force, without blocking new heads — new commits are a new world of
# code and must be reviewed.
if head_is_fresh "$PR" "$SHA"; then
  echo "::notice::The fleet reviewed sha ${SHA:0:12} $(( LAST_FRESH_AGE / 60 )) minutes ago — skipping (force=true forces a new run)."
  echo "skip=true" >> "$GITHUB_OUTPUT"
  exit 0
fi

# The CI gate, on the schedule only: a poll should not spend five lenses on a
# head CI has not yet ruled on. The selection above applies the same rule,
# and this is the recheck — a push between the selection and here restarts
# CI, and then the answer is different. That is exactly why
# reason=ci-not-green is the RACE case and not the usual path: a poll that
# waits for everyone's CI finds no candidate in the loop and ends as
# nothing-to-review, whose text says so. Manual runs bypass it (see the
# header comment), and force already bypassed it above.
# The notice is written on BOTH outcomes, not only when the fleet waits: a
# gate that is only heard when it holds back cannot be told apart from a gate
# that has stopped working. The job log should say which branch decided.
if [ "${GITHUB_EVENT_NAME}" = "schedule" ]; then
  if ci_settled "$PR" "$SHA"; then
    echo "::notice::PR #$PR: $CI_VERDICT."
  else
    echo "::notice::PR #$PR: $CI_VERDICT — the fleet waits (next poll in half an hour)."
    echo "skip=true" >> "$GITHUB_OUTPUT"
    echo "reason=ci-not-green" >> "$GITHUB_OUTPUT"
    exit 0
  fi
fi

echo "skip=false" >> "$GITHUB_OUTPUT"
