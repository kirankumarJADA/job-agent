# Architectural Conflict Review — Phase 1

Re-reading the approved master architecture against the actual P1-a codebase (not the architecture doc in isolation). Findings are separated by confidence level per your instructions: **Verified fact** (I inspected code/schema and confirmed it), **Assumption** (plausible, not confirmed), **Risk** (could cause real rework if ignored), **Recommendation** (an action, not a fact).

---

## 1. CORS + CSRF for a cross-origin SPA — gap in the master architecture, blocks P1-b

**Verified fact:** P1-a's `docker-compose.yml` and README run the backend on `:8080` and the frontend dev server on `:5173` — two different origins. The frontend's `App.tsx` already calls the backend via `fetch()`.

**Verified fact:** The master architecture (§C3) specifies "session cookies + CSRF token" for API auth, and P1-a's placeholder `SecurityConfig` doesn't configure CORS at all (it's irrelevant today because everything is `permitAll` and GET-only).

**Risk:** The moment P1-b adds real session-cookie authentication and locks down `anyRequest().authenticated()`, two things break for the SPA unless explicitly handled:
1. The browser will block the session cookie on cross-origin requests unless the backend sends proper CORS headers (`Access-Control-Allow-Origin: http://localhost:5173`, `Access-Control-Allow-Credentials: true`) and the frontend sends `credentials: 'include'`.
2. Spring Security's default CSRF protection stores the token server-side by default; a SPA needs a JS-readable CSRF cookie (`CookieCsrfTokenRepository.withHttpOnlyFalse()`) and must echo it back in a header on mutating requests.

**Recommendation:** Treat this as an explicit, named decision inside P1-b's scope (not an afterthought) — a `CorsConfigurationSource` bean scoped to the dev origin (and later, whatever the production frontend origin turns out to be), plus the JS-readable CSRF cookie repository. I'd like your sign-off on this before I build it, since it's a real addition to what the architecture doc specified, not something I should silently decide.

---

## 2. Scheduler component has no assigned owner in the P1-a…g build order

**Verified fact:** The architecture's component topology (§B1) and orchestration workflows (§C7) both describe a **Scheduler** as a first-class Phase 1 component — declared cron per source (jittered), nightly reconciler sweep, weekly benchmark replay — and explicitly call it "the missing piece, Phase 1" in the original gap analysis (§A2 gap #3).

**Verified fact:** The §D build-order table (P1-a through P1-g) assigns the **Reconciler** to P1-c explicitly ("Reconciler, DLQ notification path"), but never assigns "build the actual cron-driven Scheduler" to any lettered slice. P1-g's own scope is a one-time seed import, not a recurring scheduled job.

**Risk:** If this stays unassigned, one of two things happens: (a) it silently gets skipped and nobody notices until Phase 2 discovery needs recurring runs, or (b) it gets bolted onto whichever slice happens to be in progress when someone remembers, without a validation gate of its own.

**Recommendation:** Explicitly assign Scheduler construction to P1-c (it's the natural home next to Reconciler, and `@EnableScheduling` + a `TaskScheduler` bean is a small addition), OR add it as a named P1-h if you'd rather keep P1-c scoped to just the event/outbox mechanics. I'm flagging this now, before P1-c starts, specifically so it doesn't get decided by default.

---

## 3. JPA vs. `jsonb`/`text[]`/`citext` columns — untested impedance risk

**Verified fact:** `V001__core.sql` (already applied) uses `jsonb` extensively (e.g. `preference_sets.scoring_weights`, `job_analyses.sponsorship_evidence`), `text[]` arrays (e.g. `preference_sets.titles`, `jobs.skills_extracted`), and `citext` (e.g. `users.email`, `skills.name`).

**Verified fact:** `pom.xml` currently includes `spring-boot-starter-data-jpa` with zero `@Entity` classes written yet, and `application.yml` sets `hibernate.ddl-auto: validate`.

**Assumption (not verified — I have no Maven Central access in this sandbox to test it):** Hibernate 6 (bundled with Spring Boot 3.3.x) has native `jsonb` support via `@JdbcTypeCode(SqlTypes.JSON)`, but `text[]` arrays typically need either a converter, `@JdbcTypeCode(SqlTypes.ARRAY)`, or a third-party library (e.g. `hypersistence-utils-hibernate-63`) depending on exact version behavior. This is a known source of Hibernate schema-validation false failures (`ddl-auto: validate` comparing Hibernate's inferred column type against the actual `text[]`/`jsonb` column and rejecting the mismatch).

**Risk:** If this doesn't map cleanly, P1-d (the first slice to actually write JPA entities against these columns) could stall on framework plumbing instead of business logic, and worse, the choice to use full JPA at all (vs. Spring Data JDBC, jOOQ, or plain `JdbcTemplate`) is a foundational decision that's expensive to reverse once a dozen entities are written against it.

**Recommendation:** Before building out all of P1-d's entities, spike **one** entity end-to-end (recommend `Skill`, since it touches `citext`, a numeric range check, and a self-referencing FK) against a real Testcontainers Postgres, with `ddl-auto: validate` on, and confirm it round-trips correctly. If it doesn't, that's the moment to swap persistence approach — not after all seven P1-d entities are written the same way.

---

## 4. `resilience4j-spring-boot3` + missing `spring-boot-starter-aop` — unconfirmed

**Assumption (not verified — same Maven Central restriction):** `resilience4j-spring-boot3:2.2.0` is commonly documented as pulling in `spring-boot-starter-aop` transitively, which is required for its annotation-driven `@CircuitBreaker`/`@Retry` to actually be woven via Spring AOP proxies. I have moderate confidence this is transitive but have not resolved the dependency tree to confirm it.

**Risk:** If it's not transitive, P1-e's circuit-breaker annotations would silently do nothing (no compile error — the annotations just wouldn't be intercepted), which is a much harder bug to notice than a build failure.

**Recommendation:** When P1-e starts, explicitly run `mvn dependency:tree | grep aop` (or check `dependency:resolve`) before writing the first `@CircuitBreaker`-annotated method, and add `spring-boot-starter-aop` explicitly if it's missing. Cheap to check, expensive to debug if skipped.

---

## 5. Login/logout are not in the §C4 event registry — confirm this is intentional

**Verified fact:** The architecture's event registry (§C4) lists producer→consumer pairs for job pipeline, application pipeline, and email pipeline events, but has no `user.logged_in` / `auth.*` event type, and P1-b's scope (§D bullet 2) describes auth purely in terms of the audit log, not events.

**Assumption:** This is almost certainly intentional — a single-user personal tool has no other module that needs to *react* to a login event (no session-based feature flags, no multi-device sync in Phase 1) — but I'm calling it out explicitly rather than silently agreeing, since "audit-log only, no event" is a real architectural choice with a trade-off (an event-sourced audit trail would let you replay/query login history through the same event tooling as everything else, vs. a dedicated `audit_logs` table you query directly).

**Recommendation:** No action needed unless you want to reconsider — this is a confirm-not-fix item.

---

## 6. "Delete my data" purge endpoint has no contract yet

**Verified fact:** §D bullet 2 mentions a "delete my data" purge endpoint as part of P1-b, but §C3's API resource table has no row for it — the closest analog, `DELETE`, doesn't appear anywhere in the Phase-1 endpoint list.

**Risk:** Without an agreed contract (path, HTTP method, confirmation mechanism, exact cascade scope, whether it deletes the `users` row itself or just profile data underneath it), this gets designed ad hoc during P1-b implementation rather than reviewed up front like everything else has been.

**Recommendation:** I've drafted a proposed contract for it in the API contract document (marked **PROPOSED — needs sign-off**, not treated as settled) so it can be reviewed alongside everything else rather than improvised later.

---

## 7. Real scraped job data should not live in Flyway migrations

**Verified fact:** `V002__seed.sql` (already applied) contains 2 synthetic demo jobs written directly as SQL `INSERT`s inside a Flyway migration.

**Risk:** P1-g's DoD calls for importing ~20–30 *real* postings from live Greenhouse/Lever boards. If that's done the same way (baked into a new Flyway migration file), it creates two problems: (a) Flyway migrations are meant to be immutable and schema-focused — real-world scraped content changes/goes stale and doesn't belong version-controlled as a "migration," and (b) every future `docker compose up` from a fresh volume would silently re-insert data that's already stale by the time someone runs it.

**Recommendation:** P1-g's real job import should be an application-level one-off (a dev-profile-gated admin endpoint, or a small CLI/script hitting the same discovery connector code the Scheduler will later call), not a Flyway migration. This preserves Flyway's role as schema-plus-fixed-dev-seed only. Documented in the DB evolution plan.

---

## 8. Could any future Phase 1 decision invalidate the current P1-a foundation?

Going through each candidate deliberately:

| Decision point | Could it invalidate P1-a? | Reasoning |
|---|---|---|
| JPA vs. alternative persistence (item 3 above) | **Yes, partially** | Would mean removing `spring-boot-starter-data-jpa` from `pom.xml` and dropping `hibernate.ddl-auto` config — a real but contained change, not a schema change (V001/V002 stay valid either way, since they're plain SQL) |
| CORS/CSRF strategy (item 1) | **No** | Additive to `SecurityConfig`, doesn't require touching P1-a's schema or other modules |
| Scheduler ownership (item 2) | **No** | Wherever it lands, it's new code depending on existing `outbox_events`/`consumed_events` — doesn't require changing what P1-a built |
| Event transport (outbox+in-process now, Redis Streams later per §A3.1) | **No** | Explicitly designed as swappable via the `EventPublisher` SPI from the start — this is the one decision the architecture already protected against invalidation |
| UUIDv7 generation approach | **No** | Stable, framework-independent, already tested standalone |
| Single deployable / ArchUnit-enforced modular monolith | **No** | Holds through Phase 8 per the architecture; nothing in Phase 1 planning threatens it |

**Bottom line:** the only real invalidation risk to the current foundation is the JPA persistence choice, and it's containable — worth resolving early (item 3's spike) precisely so it doesn't compound across P1-d's seven entities first.
