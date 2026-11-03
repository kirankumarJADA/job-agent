# ROBIN vs. Tsenta — Scorecard

**Scale:** 0 Not available · 1 Concept only · 2 Implemented but unverified · 3 Verified in controlled testing · 4 Proven in production · 5 Demonstrably better.

Tsenta column = live public claims, checked directly against `tsenta.com` and `help.tsenta.com` on 2026-11-03 (claims, not independently proven performance). Robin column = this session's source-code evidence only; never inflated to match ambition.

| Category | Tsenta (public claim) | Robin (code evidence) | Robin score | Notes |
|---|---|---|---|---|
| Discovery coverage | "50,000+ career pages, 30+ ATSes" | `PlatformRegistry` registers ~25 board adapters; 8.2.5 audit historically reported 21/30 LIVE_VALIDATED, 8 unverified, 1 blocked | **2** | Robin has real adapters, not proven at Tsenta's claimed scale |
| ATS submission — Greenhouse | Implied full submission across 30+ systems | Real form inspection + guarded submit path, worker-validated, fixture-only — never `SUBMISSION_VERIFIED` by the registry's own design | **2** | Honestly labeled `SUBMISSION_AVAILABLE`, not verified |
| ATS submission — Workday | Listed among supported ATSes | Login-check only; **capability registry currently overstates this as form-fill-capable — see Gap Register P0** | **1**, and the registry bug makes it *look* like 2–3 to a candidate until fixed | Biggest single integrity risk in the whole system right now |
| Managed inbox / reply routing | "Private inbox address... routes replies to the right application" | Architecturally equivalent (`robin+token@mail.aplivenosi.com`), fully coded end-to-end, **not yet proven with a real delivered email** | **2** | Same pattern as Tsenta's, correctly chosen — just unproven |
| Application receipt detail | Shown submission receipt with field-by-field confirmation | `ApplicationReceiptService` has real SHA-256/byte-size/confirmation fields, explicit non-confirmed fallback | **2–3** (unit/integration tested, not production-proven) | Structurally sound, same honesty discipline as the rest of the backend |
| Review-before-send diff | Shown resume diff UI | Not inspected this session — needs its own evidence pass | **unscored** | Do not guess |
| Device surfaces (iOS/Android/iMessage/WhatsApp/Chrome/MCP) | All live today per Tsenta's own site | Web-only | **0–1** | Explicitly correct to defer per your own roadmap (§9/§J) — do not build to match the list |
| Pricing/usage model | $19/$39/$99 for 600/1,500/4,500 applications, 25 free | Not evaluated — no commercial layer built yet | **0** | Out of scope until core product is dependable |
| Transparency about limitations | Tsenta's own FAQ: "does not promise interviews," "does not guarantee sponsorship," "does not promise automated assistance is undetectable" | Robin's own code comments are *more* rigorous about this than Tsenta's marketing copy (e.g. `AtsCapabilityRegistry`'s explicit never-claim-`SUBMISSION_VERIFIED` rule) — **except for the Workday registry bug**, which is a real regression against Robin's own stated standard | **3 for philosophy, undermined by the one bug above** | Fix the Workday row and this becomes a genuine differentiator |

## What to actually take from this

Robin's documented engineering discipline (fail-closed config, honest capability registry, evidence-gated receipts) is, on its own terms, **already a stronger truthfulness posture than Tsenta's public marketing implies for itself** — with one live exception that must be fixed first (Workday). The gap vs. Tsenta is not "we need more features," it's "we need to finish proving the features we already built" (Phase 20) and fix the one place our own honesty system has a bug (Workday row). This matches your own roadmap's priority order exactly — no re-prioritization needed from this scorecard.

**Not scored at all:** matching/ranking quality, CV factual-accuracy rate, discovery freshness/dedup accuracy, automation-intelligence limits, UX/accessibility. Scoring these without reading the actual logic would be the same category of error as the Workday registry bug — a confident-sounding number with no evidence behind it. These need their own dedicated evidence passes (see `ROBIN_BUILD_ROADMAP.md`).
