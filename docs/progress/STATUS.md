# Milestone status
| M | Builder rounds (model) | Verdict | Date | Notes |
|---|---|---|---|---|
| M1 | orchestrated agents 2026-11-20/21 | code complete, gate pending (owner) | 2026-11-21 | T1-T12 done in code; build green (247 tests). Real-game gate not run; no JDK 8 on this PC. See M1.md. |
## Open for the owner
- Confirm D-052..D-059 (all Provisional).
- Run the M1 gate (`run.py <target> --smoke 300 --world` on the six targets) and the F3 position compare.
- Install JDK 8 (or set `BLANKET_JDK8`) for the two 1.8.9 targets.
- Review the public repo commits before any push (none pushed; blanket-emit has no remote).
