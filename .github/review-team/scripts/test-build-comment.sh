#!/usr/bin/env bash
set -euo pipefail

# Runs build-comment.sh for real against a FAKE gh, and checks when it posts.
# Run it locally or in CI's "Fleet PR selection" job: `bash
# .github/review-team/scripts/test-build-comment.sh`. Needs jq.
#
# Why this exists: on 2026-11-02 a run whose lenses all failed (OpenRouter
# credit limit) replaced PR #188's finished review with "The fleet could not
# run", through --edit-last, and the next scheduled run did it again. The rule
# since then — a failed or partial run never replaces a comment that carries
# the freshness marker — is a decision about someone else's comment, made on
# the one path that only runs when things are already going wrong. It needs
# something that can say no.
#
# The fake gh answers `pr view --json comments` from a fixture with REAL jq,
# so the expression in build-comment.sh actually runs, and records every
# `pr comment` call instead of posting it. An unknown call is an error.
#
# Mutation check: change `contains("review-fleet:v1")` in build-comment.sh's
# lookup to look for v2 and "a failed run keeps a finished review" goes red;
# drop the `[ -n "$FAILED" ]` half of its condition and "a partial run keeps
# …" does.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/build-comment.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"

cat > "$TMP/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
JQ='.'; PREV=''
for A in "$@"; do
  case "$PREV" in --jq) JQ="$A" ;; esac
  PREV="$A"
done
case "${1:-} ${2:-}" in
  'pr view')
    [ -f "$CASE/comments.json" ] || { echo "fake gh: comments unreadable" >&2; exit 1; }
    jq -r "$JQ" "$CASE/comments.json" ;;
  'pr comment')
    printf '%s\n' "$*" >> "$CASE/posted" ;;
  *) echo "fake gh: unknown call: $*" >&2; exit 64 ;;
esac
STUB
chmod +x "$TMP/bin/gh"

SHA_OLD=1111111111111111111111111111111111111111
SHA_NEW=2222222222222222222222222222222222222222
MARKED="<!-- review-fleet:v1 sha=$SHA_OLD ts=1700000000 pr=7 -->\n## ⚠️ Fleet: 13 findings"
FAILED_NOTICE="## ⚠️ The fleet could not run\n\nNo lens produced a report"
OLD_PARTIAL="<!-- review-fleet:v1 sha=$SHA_OLD ts=1700000000 pr=7 -->\n## ⚠️ Partial run — 4 of 5 lenses answered"

# comment <login> <body> — one comment as `gh pr view --json comments` gives it
# (the bot's login comes back as "github-actions" there).
comment() { printf '{"author":{"login":"%s"},"body":"%s"}' "$1" "$2"; }

FAILURES=0
CASE=''

# setup <name> <run: failed|partial|complete> <comments-json or "unreadable">
setup() {
  CASE="$TMP/$1"
  local T="$CASE/fleet"
  mkdir -p "$T/out"
  echo 'correctness security-abuse' > "$T/fleet.txt"
  case "$2" in
    failed)   echo 'correctness security-abuse' > "$T/failed.txt" ;;
    partial)  echo 'security-abuse' > "$T/failed.txt"
              mkdir -p "$T/out/correctness"
              echo '- **HIGH** `a.kt:1` — x' > "$T/out/correctness/report.md" ;;
    complete) : > "$T/failed.txt"
              for L in correctness security-abuse; do
                mkdir -p "$T/out/$L"; echo 'No findings.' > "$T/out/$L/report.md"
              done
              printf '## ✅ Fleet: no findings\n' > "$T/aggregate.md" ;;
  esac
  [ "$3" = unreadable ] || printf '{"comments":%s}\n' "$3" > "$CASE/comments.json"
  : > "$CASE/output"; : > "$CASE/summary"
  if PATH="$TMP/bin:$PATH" CASE="$CASE" REPO=o/r FLEET_TMP="$T" MODEL=m PR_NUMBER=7 \
      SHA="$SHA_NEW" RUN_URL=u GH_TOKEN=x GITHUB_OUTPUT="$CASE/output" \
      GITHUB_STEP_SUMMARY="$CASE/summary" bash "$SCRIPT" > "$CASE/log" 2>&1; then
    RC=0
  else
    RC=$?
  fi
}

expect() {  # expect <description> <condition...>
  local WHAT="$1"; shift
  if "$@"; then
    printf 'ok   %s\n' "$WHAT"
  else
    FAILURES=$(( FAILURES + 1 ))
    printf 'FAIL %s\n' "$WHAT"
    sed 's/^/     | /' "$CASE/log"
  fi
}
posted()     { [ -s "$CASE/posted" ] && grep -q -- '--edit-last' "$CASE/posted"; }
not_posted() { [ ! -e "$CASE/posted" ]; }
verdict()    { [ "$(cat "$CASE/fleet/verdict" 2>/dev/null)" = "$1" ]; }
logged()     { grep -q -- "$1" "$CASE/log"; }
kept_out()   { grep -qx 'kept=true' "$CASE/output" && grep -qx 'posted=false' "$CASE/output"; }
summarised() { grep -q 'did not replace the review' "$CASE/summary"; }
ok_rc()      { [ "$RC" -eq 0 ]; }

setup failed-marked failed "[$(comment github-actions "$MARKED")]"
expect 'a failed run keeps a finished review (no pr comment call)' not_posted
expect '… says so as an ::error' logged '::error::The fleet could not run'
expect '… and in the job summary, with outputs kept/posted=false' eval 'summarised && kept_out'
expect '… with verdict kept (the review keeps its reaction)' verdict kept

setup failed-none failed '[]'
expect 'a failed run with no fleet comment posts one' posted
expect '… verdict dirty, as before' verdict dirty

setup failed-notice failed "[$(comment github-actions "$MARKED"),$(comment github-actions "$FAILED_NOTICE")]"
expect 'a failed run replaces an earlier failure notice' posted

setup failed-old-partial failed "[$(comment github-actions "$OLD_PARTIAL")]"
expect 'a failed run replaces an old partial comment that still carries a marker' posted

setup failed-human failed "[$(comment github-actions "$FAILED_NOTICE"),$(comment someone "quoting $MARKED")]"
expect 'a human comment quoting a marker is not the fleet comment' posted

setup failed-unreadable failed unreadable
expect 'unreadable comments: keep rather than overwrite' eval 'not_posted && verdict kept && ok_rc'

setup partial-marked partial "[$(comment github-actions "$MARKED")]"
expect 'a partial run keeps a finished review' not_posted
expect '… warns and puts its report in the job summary' eval \
  "logged '::warning::Partial run' && summarised && grep -q 'a.kt:1' '$CASE/summary'"
expect '… with verdict kept (not findings: no 😕 over the kept review)' verdict kept

setup partial-none partial '[]'
expect 'a partial run with no fleet comment posts, as before' posted
expect '… verdict findings, as before' verdict findings

setup complete-marked complete "[$(comment github-actions "$MARKED")]"
expect 'a complete run replaces a finished review, as before' posted
expect '… and stamps the marker for the new head' grep -q "sha=$SHA_NEW" "$CASE/fleet/comment.md"
expect '… verdict clean' verdict clean

if [ "$FAILURES" -gt 0 ]; then
  printf '\n%s checks failed.\n' "$FAILURES"
  exit 1
fi
printf '\nAll scenarios green.\n'
