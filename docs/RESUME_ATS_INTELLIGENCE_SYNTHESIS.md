# Resume/ATS Intelligence synthesis

The six requested repositories were fetched into `research/` and inspected beyond their READMEs, including representative source, tests/examples, and licenses. Their useful ideas are used as design references only; no source code was copied into the application.

- **ResumeSkills (MIT):** workflow checklists for JD analysis, truthful tailoring, ATS formatting, version management, and cover letters. Useful as process guidance, not executable business logic.
- **Resume-parser:** ontology-based skill normalization, section-aware evidence extraction, and a weighted semantic-match presentation. Its heavyweight Python/NLP dependencies are not introduced into the Java service.
- **job-posting-structure (CC BY-NC-SA):** structured posting fields (required/preferred qualifications, education, experience, remote, benefits, skills) and an HTML-vs-LLM extraction split. The license is not used for copied code; the schema concepts inform our model.
- **rotsl/resume-tailor (MIT):** input normalization, job/company context, tailored resume plus cover letter outputs, and explicit environment validation. Its provider-dependent generation is not used as an uncontrolled side effect.
- **nuin/resume-tailor (MIT):** master fact sheet as source of truth, gap checks, second-model review, and stop-before-claiming unknown facts. These principles are enforced deterministically in the active service.
- **ats-resume-optimizer (MIT):** transparent ATS dimensions, canonical sections, exact keyword matching, single-column/plain-text formatting, and actionable scoring. Its frontend and dependency stack are not installed in this project.

## Active architecture

`JobRepository` supplies the immutable job description; `ProfileRepository` supplies verified skills, employment, education, projects, and certifications. `ResumeAtsIntelligenceService` performs deterministic extraction and normalization, maps requirements only to verified evidence, reports gaps, renders a plain-text/Markdown tailored CV, hashes the inputs, and persists an immutable `cv_versions` row plus a correlated `resume_ats_analyses` row. The optional `application_id` is checked against the same `job_id`, and the application is linked to the created CV version.

The ATS report is a transparent heuristic (keyword, requirement, evidence, and format dimensions) and explicitly makes no guarantee about a third-party ATS. No LLM output controls document claims or worker side effects. Existing `CoverLetterService` and `ApplicationAnswerService` remain separate and continue to validate factual claims; future orchestration can consume the same package IDs.

The worker receives the package through its existing `InteractionPlan`. It rejects job/application mismatches before launch, checks artifact version identity when supplied, verifies SHA-256 checksums immediately before upload, and retains the existing selector allowlist, policy gate, durable state, retries, and hard-stop behavior. Package replays return the existing deterministic analysis for an identical input hash; material job differences produce distinct CV versions.

## Security and licensing boundary

Candidate facts originate only in the persisted verified profile. Missing skills remain gaps and are never added merely because a job description contains them. CAPTCHA, anti-bot, access-control challenges, real submissions, credentials, and external mailbox access remain outside local verification. External repository licenses and provider credentials are documented dependencies; no restricted code or personal data is bundled.
