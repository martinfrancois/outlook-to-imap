---
status: accepted
date: 2026-10-27
decision-makers: ["implementing coding agent, after martinfrancois asked for [outlook-to-imap issue #14](https://github.com/martinfrancois/outlook-to-imap/issues/14) to be done"]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# The release workflow approves its own pull request runs

## Context and Problem Statement

release-please pushes the release branch and updates the release pull request with the Actions
token, `GITHUB_TOKEN`. Events from that token usually start no workflow, so
[ADR 0012](0012-releases-with-release-please-after-a-live-check.md) had the release workflow start
`ci.yml` on the release branch with `gh workflow run`.

GitHub has since changed the rule. Its
[documentation of the `GITHUB_TOKEN`](https://docs.github.com/en/actions/concepts/security/github_token)
now says that when a workflow using the token creates or updates a pull request, the `opened`,
`synchronize` or `reopened` event "creates workflow runs in an **approval-required** state", and a
user with write access has to select "Approve workflows to run". The docs added this in May 2026.

Every release pull request update since then left a `CI` and a `Commitlint` run with conclusion
`action_required`. On head `1ab40b8` of
[outlook-to-imap pull request #23](https://github.com/martinfrancois/outlook-to-imap/pull/23),
the dispatched CI run 40416431121 passed with all five jobs, `build` included, but the pull request
stayed `BLOCKED` with an empty check rollup next to the held runs 40416432822 and 40416433126.
Earlier updates of the same pull request only went green after the owner approved their held runs.

How does a release pull request update reach `CLEAN` without anyone acting on it?

## Decision Drivers

* A release pull request update must become mergeable with no click and no API call by a person.
* No new long-lived secret: no personal access token, no GitHub App private key in the repository
  secrets. The owner generally prefers no such secret in a repository.
* A failed approval must never stop a release.
* Only runs from the release workflow's own push may run without a person. A person's push to the
  release branch keeps GitHub's normal rules.
* The required check stays `build` from GitHub Actions, as the ruleset requires.

## Considered Options

* The release workflow approves its own held runs with the Actions token
* release-please pushes with a GitHub App token or a personal access token
* Change the repository's approval policy for outside contributors
* Keep the dispatch and copy its result onto the pull request head as a separate check
* Approve the held runs by hand on every update

## Decision Outcome

Chosen option: "The release workflow approves its own held runs with the Actions token", because it
makes the pull request `CLEAN` with no person acting and no new secret, and the required `build`
check comes from a real CI run on the pull request.

After release-please reports a created or updated pull request, the job "Approve the release pull
request's runs" in `.github/workflows/release-please.yml` runs:

* It is a job of its own that needs the release job and runs unless the workflow was cancelled, so
  it runs after the release steps and its failure can't skip them. It alone has `actions: write`,
  the permission the approve endpoint checks; the release job no longer has it.
* It reads the head commit from the release branch ref, because the pull request's head can lag a
  moment behind the push.
* For 90 seconds it polls every 5 seconds for the `pull_request` runs on that commit with status
  `action_required`, from this repository and with actor `github-actions[bot]`, and approves each
  with `POST /repos/{owner}/{repo}/actions/runs/{run_id}/approve`. It remembers the runs it
  approved, because a run can still be listed as held right after its approval and a second
  approval may fail.
* When it approved nothing, it fails only if the commit has no `pull_request` run at all. Then the
  pull request has no checks, because release-please reports a pull request only when it pushed
  the branch. If runs exist, someone approved them first, a person pushed after release-please, or
  GitHub stopped holding these runs; the job logs a warning.

The workflow has a concurrency group with `cancel-in-progress: false`, so two quick pushes to `main`
don't run release-please side by side and approve against the same head. A run in progress is
never cancelled. A queued run that a newer push replaces loses no release, because release-please
releases every merged release pull request still labeled `autorelease: pending`.

The dispatch step is gone. The approved `pull_request` run produces the `build` check. `ci.yml`
keeps its `workflow_dispatch` trigger, because maintainers also start CI by hand, as the owner did
in run 40335100367 on 2026-10-26.

### Consequences

* Good, because a release pull request update goes green on its own.
* Good, because no secret is added and no repository setting changes.
* Good, because CI runs once per update instead of twice: the dispatched run and, after approval,
  the `pull_request` run.
* Bad, because every release pull request update keeps a runner busy for 90 seconds, since the
  number of `pull_request` workflows isn't known to the job.
* Bad, because a release workflow run waits for the previous one to finish, release build included.
* Bad, because it relies on GitHub letting the Actions token approve runs that its own push caused.
  If GitHub closes that, the job fails and the release pull request needs a manual approval
  again, or one of the token options below.
* Neutral, because the release workflow now approves code from the release branch. That branch
  holds `main` plus the version bump and changelog that release-please wrote, and the approved runs
  get the same read-only token as any other pull request run.

### Confirmation

`.github/workflows/release-please.yml` has the job "Approve the release pull request's runs" and no
`gh workflow run`. The next release workflow run that pushes the release branch logs "Approved run"
lines in that job. Then
[outlook-to-imap pull request #23](https://github.com/martinfrancois/outlook-to-imap/pull/23), or
whichever release pull request is open, reaches `CLEAN` once CI passes:
`gh pr view <number> -R martinfrancois/outlook-to-imap --json mergeStateStatus`. No run on its head
is left at `action_required`:
`gh api "repos/martinfrancois/outlook-to-imap/actions/runs?status=action_required&head_sha=<sha>"`
lists none.

## Pros and Cons of the Options

### The release workflow approves its own held runs with the Actions token

After release-please pushes, the same job finds the held runs on the new head and approves them
through the REST API with `GITHUB_TOKEN` and `actions: write`.

* Good, because it needs no secret and no setting.
* Good, because it was tested before the change: a probe workflow with only `actions: write`
  approved the held runs 40416432822 and 40416433126 with HTTP 201, the response named
  `actions=write` as the accepted permission, CI ran, and
  [outlook-to-imap pull request #23](https://github.com/martinfrancois/outlook-to-imap/pull/23)
  showed `CLEAN`.
* Bad, because the job has to wait for GitHub to create the held runs.

### release-please pushes with a GitHub App token or a personal access token

The release workflow creates a token from a GitHub App, for example with
`actions/create-github-app-token`, or reads a personal access token, and passes it to
release-please. Pull request events from that token start workflows normally. GitHub's note in the
same documentation recommends this.

* Good, because the runs start with no approval and no polling, and the dispatch becomes
  unnecessary too.
* Bad, because the owner has to create and install an App or a token and keep its private key or
  token as a repository secret, against the owner's general preference for no such secret.
* Bad, because a personal access token acts as the owner and expires.

### Change the repository's approval policy for outside contributors

`gh api repos/martinfrancois/outlook-to-imap/actions/permissions/fork-pr-contributor-approval`
returns `{"approval_policy":"first_time_contributors"}`. The setting could move to
`first_time_contributors_new_to_github`, its loosest value.

* Good, because it would be a one-line change, if it applied.
* Bad, because it doesn't apply. The setting picks which contributors to a fork pull request need
  approval, and none of its three values turns approval off. The release branch lives in this
  repository, and the docs tie this hold to the `GITHUB_TOKEN`, not to the setting.

### Keep the dispatch and copy its result onto the pull request head as a separate check

The release workflow waits for the dispatched CI run, then creates a check or commit status named
`build` on the head commit with the same result.

* Good, because it needs no approval.
* Bad, because the ruleset requires `build` from GitHub Actions, and a hand-made check would have
  to match that source while it reports the result of a different run.
* Bad, because it keeps two CI runs per update and adds code that copies results.

### Approve the held runs by hand on every update

Keep the dispatch, and a maintainer selects "Approve workflows to run" or calls the approve
endpoint after each update, as the 1.0.0 release did.

* Good, because nothing changes.
* Bad, because every release pull request update waits for a person, which
  [outlook-to-imap issue #14](https://github.com/martinfrancois/outlook-to-imap/issues/14) asked to
  remove.

## More Information

The probe ran on 2026-10-26 from a temporary branch whose `ci.yml` held one job with
`permissions: actions: write`; run 40418835107 called the approve endpoint for both held runs, and
the branch was deleted afterwards. Another project that runs release-please with the Actions token
saw the same held runs and an empty check rollup despite a passing dispatched run
([greenlight issue #8](https://github.com/cr0cK/greenlight/issues/8)).

The real test of the job is the first release workflow run on `main` after this change that
updates the release pull request.
