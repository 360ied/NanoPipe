# Technical debt

Hub for architectural deficit and structural technical-debt notes.

- Scope: cross-cutting audits and follow-up simplification notes.
- Convention: one directory per topic, `README.md` inside (per `AGENTS.md`).
- All paths below are relative to the repository root.

## Audits

- [Whole-codebase audit (2026-11-05)](audit-2026-11-05/README.md) — extractor god-classes, client UI/navigation layering, data/persistence ownership, concurrency and error hygiene, build/infra gates, dependency and fork-divergence risks.

## Adding notes

- Dated audits: `docs/technical-debt/audit-YYYY-MM-DD/README.md`.
- Topic follow-ups (when real work lands): `docs/technical-debt/<topic>/README.md`.
- Keep each note self-contained with status, scope, evidence (`file:line`), and suggested direction.
