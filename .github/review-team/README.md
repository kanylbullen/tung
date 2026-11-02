# The review fleet (.github/review-team/)

Automated code review in review-team style: several independent reviewer
lenses against the same diff, aggregated into one PR comment. Ported from
the fleet that reviews the owner's other repositories — the same setup, the
same scripts and the same aggregation rules, vendored in the repository and
run by CI instead of by an agent on a developer machine, with the lenses
rewritten for tung.

## Layout

- `reviewers/*.md` — the lenses' role files: mandate, checklist, limits.
  The core fleet (what the workflow runs by default) is five:
  `correctness`, `security-abuse`, `architecture`,
  `code-quality-conventions`, `simplicity-scope`. Specialists can be chosen
  on a manual run: `product-ux-accessibility`, `performance-reliability`,
  `testing-strategy`, `documentation-dx`, `api-compatibility`.
- `task.md` — the shared task and the report format (one bullet per
  finding, `- **SEVERITY** \`path:line\` — ...`, with a hard length budget
  per finding), pasted after each role file.
- `aggregation.md` — the aggregator's rules: merge duplicates, drop findings
  without a concrete failure mode, settle conflicts against the code, rank
  by severity.
- `scripts/` — what the workflow actually runs: resolving the PR plus the
  freshness and CI gates, fetching the diff, running the fleet, building the
  comment. `test-resolve-pr.sh` runs the selection against a fake `gh` and
  pins its decisions, `test-build-comment.sh` does the same for when a run
  may replace the PR's comment; the `Fleet PR selection` job in `ci.yml`
  runs both on every PR.

## How it is triggered

`.github/workflows/pr-review-fleet.yml` runs on `workflow_dispatch` and on a
schedule (`12,42 * * * *` — every half hour, at offset minutes to avoid the
crowd GitHub's schedules meet on the hour, and apart from the other
repositories' fleets on the same OpenRouter account) — **deliberately not on
`pull_request`**. On that trigger the job definition lives in the PR's merge
state: GitHub runs the workflow file from the PR, and a PR that edits the
fleet gets its own version of the job with `OPENROUTER_API_KEY` and
`GITHUB_TOKEN` in the environment. No line inside the job fixes that — not
even a checkout that pins the script source to the base, because the pin
only binds when the workflow file itself is honest. The boundary has to be
in the trigger: on dispatch and schedule the workflow file and the job come
from the ref the run starts from (schedule: always the default branch;
dispatch needs write access and is therefore the way to try it out). What
stays attacker-controlled is the diff and `pr-tree/` — data, not code.

The schedule reviews the **first** open non-draft PR (most recently updated
first) whose head lacks a fresh marker and whose CI has settled — see the
freshness gate and the CI gate. The selection and resolve-pr.sh do not
filter on the base branch, deliberately: a stacked PR (base = another
feature branch) should be reviewed on its diff against its own base — the
whole story of why the base filter existed and why it was removed is in
resolve-pr.sh, at the comment "No base skip". The marker is thus both gate
and queue: "most recently updated, full stop" would be wrong for a polling
run, because if the newest PR is already reviewed the gate skips the run and
no other PR is ever reviewed. With no candidate the run is skipped, green
and quiet. Scheduled runs share a concurrency group and queue behind each
other: a poll that hits a review in progress becomes a quick skip once the
marker is there, instead of a double review paid for twice.
(`cancel-in-progress` is therefore off — a new poll every half hour that
killed reviews taking longer than that would cancel them for ever.)

Each lens is a cline call of its own (model from the repository variable
`FLEET_MODEL`, see below, via OpenRouter, key from the repository secret
`OPENROUTER_API_KEY` — added by hand by the owner). The lenses run in
parallel on a GitHub-hosted `ubuntu-latest` runner (free for a public
repository), each with its own cline state; the longest path is one lens
plus the aggregator, and that is what sets the job's time budget. The
aggregator has its own, larger time budget (600 s against the lenses' 300)
— it reads every report and also opens the files concerned. The job has
`timeout-minutes: 60`: the fleet's first parallel run took 36 min 41 s
against 40 — three minutes' margin kills a slow run after the reports but
before the comment is posted. The findings are posted as a PR comment.

An empty key is the state the system starts in (it is added by hand) and
fails the job in seconds with `::error::OPENROUTER_API_KEY` — never as six
timeouts and never as a green check.

### The model

```yaml
MODEL: ${{ vars.FLEET_MODEL || 'z-ai/glm-5.3-flash' }}
```

Set the repository variable `FLEET_MODEL` (Settings > Secrets and variables
> Actions > Variables) to any OpenRouter model id — for instance a Claude
model while there are credits for it — and the next run uses it. Remove the
variable and the fleet falls back to `z-ai/glm-5.3-flash`. The comment's
footnote names the model each review ran on.

### One comment per PR

The comment is posted with `gh pr comment --edit-last --create-if-none`
(needs gh ≥ 2.31 on the runner): a PR has ONE fleet comment that is updated
in place on new pushes and reruns — never a new copy per event, and the
freshness marker therefore cannot be pushed out of the comment window.

**A run may replace that comment only with something at least as
complete.** A failed run ("The fleet could not run") or a partial run (some
lenses missing) first reads the comment `--edit-last` would edit — the last
comment by `github-actions[bot]`, so a human comment is never looked at.
If it carries a freshness marker for *any* head (a complete review or the
diff-too-large decision) and no partial banner, it is left untouched: the
failure is reported in the job (`::error` for a failed run, which stays red;
`::warning` for a partial one) and what the run would have posted goes into
the job summary. The PR keeps that review's 👍 or 😕; only the run's 👀 is
removed. With no such comment — none at all, or a failure or partial notice
— the run posts or edits as before. If the comments cannot be read, the run
keeps rather than overwrites. A complete run, which stamps the marker,
replaces the comment as always.

Why: on 2026-11-02 a run with every lens failing (OpenRouter credit limit)
replaced PR #188's finished review of 13 findings with "The fleet could not
run", and a scheduled run 16 minutes later did it again; the review
survived only in GitHub's edit history. Nothing in the rule weakens the
freshness gate: the failed run still stamps nothing, so it can be retried
without `force`, and the gate keys on the head sha, so a kept review of an
old head is never fresh for a new one. `test-build-comment.sh` pins the
decisions with a fake `gh`.

### The verdicts

GitHub has no ✅ or ⚠️ reaction, so the start marker is 👀 and the final
verdict is 👍 or 😕; ✅/⚠️ are in the comment.

- 👍 — every lens answered and the finding count (the grep format **and**
  the aggregator's heading) is zero.
- 😕 — findings were found.
- No reaction — a partial run (some lenses did not answer), a failed run or
  a skip. Those are not verdicts on the PR. When such a run leaves a
  finished review in place (see above), that review's reaction stays.
- A partial run is said at the top of the comment: who did not answer, and
  that the verdict rests only on those who did. A 👍 needs every lens to
  answer — four of five is not enough.

### The freshness gate

Every fleet comment carries the marker
`<!-- review-fleet:v1 sha=… ts=… pr=… -->`. The same head handled within
24 h is skipped; `force=true` forces a new run. The marker is written only
when the fleet has taken a position on the head: a real review where ALL
lenses answered, or the diff-too-large decision (deterministic for the head
— a rerun cannot land anywhere else). "The fleet could not run" and "Partial
run" NEVER carry the marker, so a broken or half-finished run can always be
rerun without force. resolve-pr.sh also ignores markers in comments that
carry the partial banner — older runs stamped them, and they must not lock a
PR for a day.

### The CI gate

The schedule does not review a head whose checks have not settled: every
check must have finished without failing. `SKIPPED` and `NEUTRAL` count as
green — a check that deliberately did not run has nothing to say. Five
lenses on code CI has already rejected are spent on something that will
change anyway, and it costs real money: the repositories that run the fleet
share ONE daily budget at OpenRouter, and on 2026-09-09 it ran out at 09:05
UTC after 21 reviews in a day against one or two the days before — mostly
intermediate states of two PRs that were pushed again while being reviewed.

**The cap `CI_WAIT_HOURS` (4 h) is the other half and it is not
optional.** Without it the gate becomes a silent shutdown as soon as a check
never turns green: a self-hosted runner that is down (tung's iOS job may run
on a Mac), a job that hangs in the queue until GitHub fails it, a red job
nobody intends to fix. If nothing has happened on the head for that long it
is reviewed anyway, and the notice says why. The clock is the latest thing
that happened on the head — the commit itself, or a check that started after
it. Both branches have been run against reality in the fleet's other
repositories: a PR with a check red for 236 hours was let through with the
notice saying so, and one with a red check on a fresh head waited.

The gate does not tell required checks from the rest, and cannot: which are
required is in the branch protection, which GITHUB_TOKEN may not read. A
check that is red for infrastructure reasons thus holds the review back
until the cap releases — four hours per head, not a missed review. That is
the price of not keeping a list of names that rots as soon as a job is
renamed, and also why the cap is four hours and not a day.

The gate holds back **only on a definite answer**: if the rollup or the
timestamp cannot be read the PR is reviewed as before. An unanswered API
call must not silence the fleet, for the same reason the cap exists. The
notice is written on both outcomes — a gate that is only heard when it holds
back cannot be told apart from a gate that has stopped working.

And it applies **only to the schedule**. `workflow_dispatch` reviews at
once, with or without `force`: a human who asks for a review should get
one, red CI or not.

## Two trees: the code that runs and the tree under review

The workflow checks out two things, in different directories. The shallow
checkout at the root of the workspace is the code that runs —
`.github/review-team/` is always executed from there — and is on
`github.ref`, the ref the run started from: the default branch on schedule,
the chosen ref on dispatch (which needs write access). The workflow file was
taken from the same ref, so the scripts cannot have been swapped by the PR
under review. The PR's merge state goes into `pr-tree/` (checkout with
`path:`) and is only readable data for the lenses and the aggregator: no
step executes anything from it. The separation still carries weight on
dispatch and schedule: a prompt-injected lens that wrote in the root could
edit templates and scripts that are read later in the same run — the
aggregation and the comment build run after the lenses — or plant a forged
freshness marker on a shared path.

The consequence is by design: **the fleet does not review the PR that
introduces or changes it.** Before it is merged there is no trigger that
runs anything from the PR tree — that is the point of `pull_request` being
gone — and after the merge the PR is closed and never comes up in the
schedule's selection. A new fleet is tried out with `workflow_dispatch` from
the fleet branch — there the person running it chooses the ref, not the PR.

## Where the fleet's state lives

Everything the job writes — diff, reports, prompts, logs, cline state, npm
cache — is in `$FLEET_TMP` under the runner's temp directory, deliberately
outside both trees: the lenses act in `pr-tree/` and must not be able to
read each other's reports or tasks from it. The reports also live ONE
DIRECTORY PER LENS — `$FLEET_TMP/out/<lens>/report.md` — because all lenses
share an OS user, and a report on a shared, guessable path could have been
overwritten by a prompt-injected lens: forging or erasing its siblings'
findings before the aggregator read them. It is cleaned up in an always
step.

The reports are themselves untrusted content — agent output that read
untrusted PR data — and are treated as such before they are posted:
`build-comment.sh` neutralises the freshness marker pattern in the report
and final report bodies (a planted marker must not play the gate's key),
and the aggregator is told to verify claims against the code.

A soft failure should leave a trace: if a lens or the aggregator fails,
`$FLEET_TMP/logs/` is kept as a job artifact (and the tail of
`logs/aggregate.log` goes into the job summary) — "Clean up" would
otherwise remove exactly the log that answered what went wrong. On a
successful run nothing is kept.

## Rerunning / steering

```sh
# Rerun a PR (forces past the freshness gate):
gh workflow run pr-review-fleet.yml -R kanylbullen/tung -f pr=<number> -f force=true

# A narrower fleet, or specialists:
gh workflow run pr-review-fleet.yml -R kanylbullen/tung -f pr=<number> \
  -f reviewers=correctness,security-abuse,api-compatibility
```

## Turning it off

Remove the `schedule` trigger in `pr-review-fleet.yml` — then the fleet
runs only when someone runs `workflow_dispatch` by hand. To turn everything
off, remove the whole `jobs:` block. The lenses do not affect merging: the
fleet reports, it does not block.

## Validating changes to the workflow

```sh
yamllint -d '{extends: default, rules: {line-length: {max: 120},
  truthy: {check-keys: false}}}' .github/workflows/pr-review-fleet.yml
actionlint
bash -n .github/review-team/scripts/*.sh
bash .github/review-team/scripts/test-resolve-pr.sh
bash .github/review-team/scripts/test-build-comment.sh
```
