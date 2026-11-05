---
name: pipepipe-pedantic-doc-review
description: >-
  Execute an adversarial pedantic review of design docs, proposals, or audit
  notes in the PipePipe repository: launch one or more independent reviewer
  subagents to verify claims against code and probe for gaps, then provide a
  critical assessment of the findings. Use whenever the user asks for a
  "pedantic doc review", "design doc review", "proposal review", or
  exhaustive inspection of a document under docs/.
---

# PipePipe: Pedantic Design-Doc Review

Launch independent reviewer subagent(s) to conduct an exhaustive review of
a design doc or proposal, followed by an adversarial critical assessment
by the parent agent. Unlike the code-review skill (which slices by file),
this skill fans out by review lens: the ground truth of a doc is the code
it describes, so every behavioral claim must be verified, not opined on.

## 1. Identify Review Target

Identify the target document and scope:
- **Default scope**: A single document, usually `docs/<topic>/README.md`,
  explicitly named by the user. Docs are small; one doc is the normal case.
- **Extended scope**: A doc plus its referenced implementation files, or
  several related docs explicitly requested by the user. The referenced
  code is read-only context, never itself the review target.
- NEVER expand scope to unrequested docs or to the codebase at large. If
  the reviewer stumbles on code defects while verifying claims, flag them
  as incidental findings (same convention as the code-review skill).

## 2. Size the Review

Docs are small, so slice-by-file fan-out never applies. Choose instead:

- **Single reviewer** (the common case): the doc is short (roughly ≤300
  lines) or the user asked for a quick pass. Run the §3 single-reviewer
  prompt, which requires three explicit passes in one report.
- **Fan-out by lens**: longer or higher-stakes docs (new subsystem
  designs, multi-slice implementation proposals). Launch 2–3 reviewers in
  a single batch, one per lens below. Never exceed 3 lenses unless the
  user approves; lens reports overlap by design and the parent merges
  them.
  - **Lens 1 – Accuracy**: every behavioral, structural, and historical
    claim checked against the code at the cited (or discoverable)
    `file:line`. Verdict per claim: confirmed, doc-wrong, code-wrong, or
    unverifiable.
  - **Lens 2 – Completeness**: missing states, edge cases, concurrency
    and lifecycle paths, error paths, and interactions with adjacent
    systems (other tabs, background workers, rotation, empty/error
    conditions). Compares the doc's state tables and test plan against
    what the code paths imply.
  - **Lens 3 – Implementability & UX**: could an implementer build from
    this without guessing? Slices ordered and independently shippable,
    acceptance criteria falsifiable, user-facing behavior describable from
    the doc alone, scope leaks and unanswered load-bearing questions
    called out.

## 3. Launch Reviewer Subagent(s)

No builds, lint, or tests mid-flight. Each task is self-contained (doc
path, lens, read-only instructions).

**Single reviewer** (replacing `<doc-path>` with the concrete path):

```text
Perform an exhaustive pedantic review of the design doc at <doc-path>.

Instructions:
- Make three passes and report them under separate headings:
  1. Accuracy: verify every behavioral, structural, and historical claim
     against the repository code. Follow the doc's file:line references;
     where references are missing, locate the relevant code yourself.
     Verdict each non-trivial claim as confirmed, doc-wrong, code-wrong,
     or unverifiable — with corrected file:line evidence either way.
  2. Completeness: enumerate missing states, edge cases, concurrency and
     lifecycle paths, error paths, and adjacent-system interactions the
     doc does not cover but the code implies.
  3. Implementability & UX: assess whether an implementer could build from
     this without guessing (slice ordering, acceptance criteria, scope
     discipline) and whether the user-facing behavior is fully describable
     from the doc alone.
- "See something, say something": if you stumble upon pre-existing defects
  in the surrounding code while verifying claims (even if unrelated to the
  doc), flag them as incidental findings, clearly marked as code defects,
  not doc defects.
- Check the doc against repo documentation conventions: one directory per
  topic with README.md inside, paths relative to the repo root, a
  status/scope header, no committed secrets or machine-local paths.
- This is a strictly read-only review: do not edit files, stage commits,
  or attempt fixes; your sole purpose is to identify and report issues.
- Do not fabricate issues or report false positives. If no issues exist
  within a category, explicitly state that none were identified.

Report format:
- Group findings into four tiers:
  - [INACCURACY]: doc claims contradicted by code (or vice versa), stale
    file:line references, wrong mechanism descriptions. Include the
    corrected reference.
  - [GAP]: missing states, edge cases, error/concurrency/lifecycle paths,
    or test-plan holes.
  - [UNBUILDABLE]: an implementer would have to guess — unordered or
    unscoped slices, unfalsifiable acceptance criteria, load-bearing open
    questions, user-facing behavior not derivable from the doc.
  - [PEDANTIC]: prose, structure, naming, and repo-convention hygiene.
- Identify each issue with clickable markdown links in
  [`<file>:<line>`](file://<absolute-path>#L<line>) format, exact line
  numbers (doc lines for prose issues, code lines for accuracy issues),
  and a clear description of the problem.
- Send your complete report back to the caller.
```

**Fan-out** (one `task` call with a single `tasks[]` batch — one
`reviewer` per lens, all lenses in the same batch; shared tier/report
contract across lenses). Per-lens prompt (replace bracketed
placeholders):

```text
Pedantic design-doc review of <doc-path>. Your lens owns <LENS-NAME>:
<LENS-DESCRIPTION> (lens definitions and shared contract per the skill).

Instructions:
- Your lens owns the above, but flag what you see even if it belongs to
  another lens, and note the cross-lens reference. Other reviewers cover
  other lenses; overlapping inspection is expected and the caller merges
  duplicates.
- "See something, say something": if you stumble upon pre-existing defects
  in the surrounding code while verifying claims, flag them as incidental
  findings (code defects, clearly marked, not doc defects).
- <LENS-SPECIFIC-GUIDANCE: lens 1 follows file:line references into code
  and verdicts each claim confirmed/doc-wrong/code-wrong/unverifiable;
  lens 2 diffs the doc's state tables and test plan against implied code
  paths; lens 3 role-plays the implementer and the user in turn.>
- This is a strictly read-only review: do not edit files, stage commits,
  or attempt fixes; your sole purpose is to identify and report issues.
- Do not fabricate issues or report false positives. If no issues exist
  within a category, explicitly state that none were identified.

Report format:
- Group findings into four tiers:
  - [INACCURACY]: doc claims contradicted by code (or vice versa), stale
    file:line references, wrong mechanism descriptions. Include the
    corrected reference.
  - [GAP]: missing states, edge cases, error/concurrency/lifecycle paths,
    or test-plan holes.
  - [UNBUILDABLE]: an implementer would have to guess — unordered or
    unscoped slices, unfalsifiable acceptance criteria, load-bearing open
    questions, user-facing behavior not derivable from the doc.
  - [PEDANTIC]: prose, structure, naming, and repo-convention hygiene.
- Identify each issue with clickable markdown links in
  [`<file>:<line>`](file://<absolute-path>#L<line>) format, exact line
  numbers, and a clear description of the problem (note if
  incidental/pre-existing or cross-lens).
- Send your complete report back to the caller.
```

## 4. Deduplicate, Assess & Report

Do not merely relay or concatenate the subagent report(s). Lens reports
overlap by design — merge them:

1. **Deduplicate** (fan-out only): collapse identical or overlapping
   findings (same doc section or code location, same root cause) into one
   entry; record which lenses reported it as corroboration, not as
   separate issues.
2. **Evaluate Findings**:
   - **Concurred**: Validate legitimate inaccuracies, gaps, unbuildable
     points, and pedantic issues that warrant remediation. For
     `[INACCURACY]` verdicts, independently confirm doc-wrong vs.
     code-wrong before concurring — reviewers sometimes trust the doc
     over the code.
   - **Contested / False Positives**: Provide technical rebuttals and
     rationale for findings that misread the code, demand out-of-scope
     coverage, or mistake intentional doc trade-offs (e.g. deliberately
     deferred slices) for gaps.
   - **Incidental / Pre-existing**: Keep code defects found during
     verification in a separate section; they are not doc defects and
     must not block doc acceptance.
3. **Present to User**: Deliver the findings (with lens attribution for
   fan-out) plus your critical assessment, identifying actionable next
   steps — what must be corrected in the doc before implementation vs.
   what is polish.
```

