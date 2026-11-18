# Branch protection for `main`

Not applied by CI or by a PR: it is a repository setting the owner approves and
applies. Today `main` has no protection and no rulesets, and auto-merge is off
in the repository settings, so `gh pr merge --auto` is refused or (once
enabled) merges at once. After this:

- the one required status check is **`CI passed`** (`ci.yml`, see CLAUDE.md,
  "Required checks and smoke tests");
- the branch must be up to date with `main` before merging is **off** (the
  owner merges several PRs a day; turn it on if a green PR ever breaks `main`);
- **no reviews are required** (the owner is the only maintainer);
- admins are **not** enforced, so the owner can merge with `gh pr merge --admin`
  when the Mac runner is offline or a check is broken;
- force pushes and deleting `main` are refused.

## Classic branch protection

```sh
gh api -X PUT repos/kanylbullen/tung/branches/main/protection --input - <<'JSON'
{
  "required_status_checks": { "strict": false, "checks": [ { "context": "CI passed" } ] },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

The check's context is the job's `name`, `CI passed`. GitHub lists a context
only once it has run, so apply this after the first run of the new `ci.yml`
on a PR.

Auto-merge must be allowed for `--auto` to work at all:

```sh
gh api -X PATCH repos/kanylbullen/tung -F allow_auto_merge=true -F delete_branch_on_merge=true
```

`gh pr merge --auto --squash <n>` then waits for `CI passed`.

## Rollback

```sh
gh api -X DELETE repos/kanylbullen/tung/branches/main/protection
```
