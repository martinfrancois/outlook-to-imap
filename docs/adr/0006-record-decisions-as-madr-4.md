---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# Record decisions as MADR 4 architecture decision records

## Context and Problem Statement

The first five records were short free-form notes: a title and a few paragraphs. They said what was
chosen but rarely what else was considered, which facts decided it, or how to check that it still
holds. The decisions behind them were made in one long working session with an agent, and most of
the reasoning existed only in that conversation. Once it is gone, a maintainer who meets an odd line
of code has no way to tell a deliberate choice from an oversight. In what shape should this
repository record its decisions, so that the reasoning survives the conversation?

## Decision Drivers

* A maintainer or agent must find the reasoning without the conversation that produced it.
* Rejected options and the facts that decided a choice are the parts that can't be reconstructed
  from the code later.
* The owner's other repositories already use MADR with the same agent rules, so the habits carry over
  without relearning.
* A test must be able to check the shape, so a record can't decay into a free-form note again.
* The check must run in the existing build, `./mvnw -B verify`, without a second toolchain.

## Considered Options

* MADR 4 records under `docs/adr/`, checked by a JUnit test
* Keep the short free-form records
* Nygard-style records (Context, Decision, Status, Consequences)
* Rationale in AGENTS.md and code comments only

## Decision Outcome

Chosen option: "MADR 4 records under `docs/adr/`, checked by a JUnit test", because it is the only
option with a fixed place for rejected alternatives and for how to confirm a decision, it matches the
owner's other repositories, and the check runs in the Maven build CI already runs.

### Consequences

* Good, because every record now names the options that lost and why, which stops the next person
  from retrying them.
* Good, because `AdrConformanceTest` fails the build on a record that lacks a section or leaves one
  empty, keeps a template placeholder, cites an issue as a bare number or with vague link text,
  chooses an option it didn't list, contains an em dash, or uses an unknown status.
* Bad, because a small decision now costs a full record with front matter and option subsections.
  AGENTS.md says when that cost is owed.
* Neutral, because the five earlier records, which shipped in 1.0.0, were rewritten into the new
  shape the same day and kept their decisions unchanged.

### Confirmation

`./mvnw -B verify` runs `AdrConformanceTest`, which checks every file in `docs/adr/` except
`adr-template.md`. AGENTS.md has an "Architecture decision records" section with the triggers for
writing a record and the rules for writing one.

## Pros and Cons of the Options

### MADR 4 records under `docs/adr/`, checked by a JUnit test

One Markdown file per decision, copied from the official MADR 4 template, with YAML front matter and
the fixed MADR headings. A JUnit test in `src/test` parses the front matter with the YAML library the
service already uses and checks the headings, the status, the links and the file names.

* Good, because the shape asks for rejected options, consequences and a confirmation step.
* Good, because the same template, status rules and link style are in use in the owner's other
  repositories.
* Good, because the test runs in the build CI already runs, on every Java version in the matrix.
* Bad, because the format is long for a one-line decision.

### Keep the short free-form records

Each record stays a title and a few paragraphs in whatever shape its author chose, as records 0001
to 0005 were.

* Good, because a record costs almost nothing to write.
* Bad, because nothing makes the author list rejected options or how to confirm the decision. Of
  the first five, four named an option they rejected, each in a sentence or two.
* Bad, because a test can't check a shape the files don't have.

### Nygard-style records (Context, Decision, Status, Consequences)

One file per decision with four sections, as Michael Nygard first described them.

* Good, because the format is short and widely known.
* Bad, because it has no section for the options that lost, the part this repository most needs.
* Bad, because it differs from the owner's other repositories, so their agent rules wouldn't apply.

### Rationale in AGENTS.md and code comments only

Write the reason for each choice next to the code or in AGENTS.md, and keep no separate records.

* Good, because the reason sits where it applies.
* Bad, because AGENTS.md is read at the start of every agent session, so every rejected option would
  cost context on every run.
* Bad, because comments must stay short and describe the current code, so there is no room for the
  alternatives, the measurements or a superseded decision.

## More Information

The owner asked for the switch on 2026-10-26, together with a review of the working session so the
records hold everything that was decided in it. The format is described at
[MADR](https://adr.github.io/madr/). The template is `adr-template.md` in this directory.
