package com.personal.jobagent.jobs;

import com.personal.jobagent.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /jobs, GET /jobs/{id}, POST /jobs/import-url per docs/contracts/api.md.
 * Read-side only per P1-g's scope — analysis/score/decision_trace are null
 * for every job here since those pipelines don't exist until Phase 3/4,
 * which the API contract already documents as expected, not a bug.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobsController {

    private static final java.util.Set<String> VALID_STATUSES = java.util.Set.of(
            "DISCOVERED", "FILTERED_OUT", "ANALYSED", "SCORED", "DECIDED", "ARCHIVED", "PIPELINE_ERROR");

    private final JobRepository jobRepository;
    private final JobSeedService jobSeedService;
    private final com.personal.jobagent.security.OwnerContext ownerContext;
    private final com.personal.jobagent.application.ApplicationPipelineService pipeline;

    public JobsController(JobRepository jobRepository,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) JobSeedService jobSeedService,
                          com.personal.jobagent.security.OwnerContext ownerContext,
                          com.personal.jobagent.application.ApplicationPipelineService pipeline) {
        this.jobRepository = jobRepository;
        this.jobSeedService = jobSeedService;
        this.ownerContext = ownerContext;
        this.pipeline = pipeline;
    }

    /**
     * The human half of the decision engine: REVIEW matches are stored but
     * never auto-acted on, so the owner needs an explicit way to convert one
     * into an application. Creation is the same idempotent path the APPLY
     * flow uses (pre-check + partial unique index), so double clicks and
     * replays cannot create a second application.
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<?> applyToJob(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required to apply"));
        }
        try {
            var created = pipeline.createApplicationFromMatch(profileId, id);
            if (created == null || created.applicationId() == null) {
                return ResponseEntity.status(409).body(Map.of("error", "application could not be created"));
            }
            return ResponseEntity.status(created.created() ? 201 : 200).body(Map.of(
                    "application_id", created.applicationId().toString(),
                    "created", created.created(),
                    "status", created.status()));
        } catch (com.personal.jobagent.apply.DuplicateApplicationException duplicate) {
            // Cross-source duplicate protection. The identified record is the
            // CALLER'S OWN application — nothing about another candidate.
            return ResponseEntity.status(409).body(Map.of(
                    "error", "you already have an application for this role: " + duplicate.matchReason(),
                    "code", "DUPLICATE_APPLICATION",
                    "duplicateApplicationId", duplicate.existingApplicationId().toString(),
                    "duplicateJobId", String.valueOf(duplicate.existingJobId())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public ResponseEntity<?> listJobs(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "20") int limit,
                                       HttpServletRequest httpRequest) {
        if (status != null && !VALID_STATUSES.contains(status)) {
            ApiError error = ApiError.of(400, "Invalid status",
                    "status must be one of " + VALID_STATUSES, httpRequest.getRequestURI(),
                    String.valueOf(org.slf4j.MDC.get("correlation_id")));
            return ResponseEntity.badRequest().body(error);
        }

        int boundedLimit = Math.max(1, Math.min(limit, 100));
        JobRepository.FeedPage page = jobRepository.findFeed(
                ownerContext.profileIdOrNull(), status, q, boundedLimit, cursor);
        return ResponseEntity.ok(Map.of(
                "items", page.items().stream().map(JobsController::toWire).toList(),
                "next_cursor", page.nextCursor() != null ? page.nextCursor() : ""));
    }

    /**
     * Wire format of a FIND posting. Keys are spelled out in snake_case on
     * purpose: the frontend contract (types.ts {@code Job}) has always been
     * snake_case, but the record used to be serialized directly, which produced
     * camelCase and left company, location, workplace type and skills blank in
     * the UI. Building the map explicitly makes the contract visible and testable.
     *
     * <p>Only the caller's own match is included (see {@link JobRepository#findFeed}).
     * The shared {@code jobs.filter_reasons} column is deliberately NOT exposed:
     * it is written by whichever candidate's hard filters ran first, so it is not
     * this caller's result.
     */
    static Map<String, Object> toWire(JobRepository.FeedItem item) {
        JobRecord job = item.job();
        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("id", job.id());
        wire.put("source_id", job.sourceId());
        wire.put("external_id", job.externalId());
        wire.put("company_id", job.companyId());
        wire.put("company_name_raw", job.companyNameRaw());
        wire.put("title", job.title());
        wire.put("location_raw", job.locationRaw());
        wire.put("city", job.city());
        wire.put("country", job.country());
        wire.put("remote_type", job.remoteType());
        wire.put("employment_type", job.employmentType());
        wire.put("experience_level", job.experienceLevel());
        wire.put("salary_min", job.salaryMin());
        wire.put("salary_max", job.salaryMax());
        wire.put("salary_currency", job.salaryCurrency());
        wire.put("description_text", job.descriptionText());
        wire.put("skills_extracted", job.skillsExtracted() == null ? List.of() : job.skillsExtracted());
        wire.put("application_url", job.applicationUrl());
        wire.put("canonical_url", job.canonicalUrl());
        wire.put("posted_at", job.postedAt() == null ? null : job.postedAt().toString());
        wire.put("status", job.status());
        wire.put("first_seen_at", item.firstSeenAt().toString());
        wire.put("last_seen_at", item.lastSeenAt().toString());
        wire.put("stale", item.stale());
        wire.put("removed", item.removed());
        wire.put("source_name", item.sourceName());
        wire.put("source_kind", item.sourceKind());
        wire.put("match_score", item.matchScore());
        wire.put("match_recommendation", item.matchRecommendation());
        return wire;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJob(@PathVariable UUID id, HttpServletRequest httpRequest) {
        return jobRepository.findFeedItem(ownerContext.profileIdOrNull(), id)
                .<ResponseEntity<?>>map(item -> {
                    // Map.of() throws NPE on null values — analysis/score
                    // are legitimately null here (Phase 3/4 pipelines
                    // don't exist yet), so a mutable map is required.
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("job", toWire(item));
                    body.put("analysis", null);
                    body.put("score", null);
                    // The CALLER's own match result. Jobs themselves are a shared
                    // catalogue (public postings, globally deduplicated), so the
                    // per-candidate part of a job — how well it matches you, and
                    // why — is served from job_matches scoped to the caller. It is
                    // null when this candidate has not scored the posting, which
                    // is why the previous implementation must never be restored:
                    // it kept one candidate's score on the shared row.
                    body.put("match", jobRepository.findMatch(ownerContext.profileIdOrNull(), id).orElse(null));
                    body.put("decision_trace", java.util.List.of());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> {
                    ApiError error = ApiError.of(404, "Not found", "No job with that id",
                            httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id")));
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
                });
    }

    public record ImportUrlRequest(String url) {
    }

    /**
     * Stub per the architecture's own phasing: full URL resolution
     * (LinkedIn → underlying ATS board detection) is Phase 2 scope. This
     * validates the URL is well-formed and accepts the request without
     * attempting resolution.
     */
    @PostMapping("/import-url")
    public ResponseEntity<?> importUrl(@RequestBody ImportUrlRequest request, HttpServletRequest httpRequest) {
        try {
            new URI(request.url()).toURL();
        } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
            ApiError error = ApiError.of(400, "Invalid URL", "url must be well-formed",
                    httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id")));
            return ResponseEntity.badRequest().body(error);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "RESOLUTION_PENDING"));
    }

    @PostMapping("/seed-uk")
    public ResponseEntity<?> seedUkJobs() {
        if (jobSeedService != null) {
            int count = jobSeedService.seedRealUkJobs();
            return ResponseEntity.ok(Map.of("status", "SEEDED", "count", count));
        }
        return ResponseEntity.ok(Map.of("status", "SEEDER_NOT_AVAILABLE"));
    }
}
