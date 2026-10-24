package com.personal.jobagent.jobs;

import com.personal.jobagent.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    /** The real {@code jobs.remote_type} check-constraint values (V001) — never a guessed vocabulary. */
    private static final java.util.Set<String> VALID_REMOTE_TYPES = java.util.Set.of(
            "REMOTE", "HYBRID", "ONSITE", "UNKNOWN");

    /** The real {@code job_analyses.sponsorship_status} check-constraint values (V001). */
    private static final java.util.Set<String> VALID_SPONSORSHIP_STATUSES = java.util.Set.of(
            "LIKELY_AVAILABLE", "POSSIBLE", "UNKNOWN", "LIKELY_NOT_AVAILABLE", "NOT_AVAILABLE");

    private final JobRepository jobRepository;
    private final JobSeedService jobSeedService;
    private final com.personal.jobagent.security.OwnerContext ownerContext;
    private final com.personal.jobagent.application.ApplicationPipelineService pipeline;
    private com.personal.jobagent.discovery.platform.BoardImportService boardImport;

    public JobsController(JobRepository jobRepository,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) JobSeedService jobSeedService,
                          com.personal.jobagent.security.OwnerContext ownerContext,
                          com.personal.jobagent.application.ApplicationPipelineService pipeline) {
        this.jobRepository = jobRepository;
        this.jobSeedService = jobSeedService;
        this.ownerContext = ownerContext;
        this.pipeline = pipeline;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBoardImport(com.personal.jobagent.discovery.platform.BoardImportService boardImport) {
        this.boardImport = boardImport;
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
                                       @RequestParam(required = false) String location,
                                       @RequestParam(required = false) String remote_type,
                                       @RequestParam(required = false) String employment_type,
                                       @RequestParam(required = false) String experience_level,
                                       @RequestParam(required = false) String salary_min,
                                       @RequestParam(required = false) String sponsorship,
                                       @RequestParam(required = false) String posted_after,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "20") int limit,
                                       HttpServletRequest httpRequest) {
        if (status != null && !VALID_STATUSES.contains(status)) {
            return badRequest(httpRequest, "Invalid status", "status must be one of " + VALID_STATUSES);
        }
        String remoteType = blankToNull(remote_type);
        if (remoteType != null) {
            remoteType = remoteType.toUpperCase(java.util.Locale.ROOT);
            if (!VALID_REMOTE_TYPES.contains(remoteType)) {
                return badRequest(httpRequest, "Invalid remote_type", "remote_type must be one of " + VALID_REMOTE_TYPES);
            }
        }
        String sponsorshipStatus = blankToNull(sponsorship);
        if (sponsorshipStatus != null) {
            sponsorshipStatus = sponsorshipStatus.toUpperCase(java.util.Locale.ROOT);
            if (!VALID_SPONSORSHIP_STATUSES.contains(sponsorshipStatus)) {
                return badRequest(httpRequest, "Invalid sponsorship", "sponsorship must be one of " + VALID_SPONSORSHIP_STATUSES);
            }
        }
        String sortKey = blankToNull(sort);
        if (sortKey != null && !JobRepository.VALID_SORTS.contains(sortKey)) {
            return badRequest(httpRequest, "Invalid sort", "sort must be one of " + JobRepository.VALID_SORTS);
        }
        BigDecimal salaryMin = null;
        if (blankToNull(salary_min) != null) {
            try {
                salaryMin = new BigDecimal(salary_min.strip());
                if (salaryMin.signum() < 0) throw new NumberFormatException("negative");
            } catch (NumberFormatException e) {
                return badRequest(httpRequest, "Invalid salary_min", "salary_min must be a non-negative number");
            }
        }
        Instant postedAfter = null;
        if (blankToNull(posted_after) != null) {
            try {
                postedAfter = Instant.parse(posted_after.strip());
            } catch (DateTimeParseException e) {
                return badRequest(httpRequest, "Invalid posted_after", "posted_after must be an ISO-8601 instant");
            }
        }

        int boundedLimit = Math.max(1, Math.min(limit, 100));
        JobRepository.FeedFilters filters = new JobRepository.FeedFilters(
                blankToNull(location), remoteType, blankToNull(employment_type), blankToNull(experience_level),
                salaryMin, sponsorshipStatus, postedAfter);
        try {
            JobRepository.FeedPage page = jobRepository.findFeed(
                    ownerContext.profileIdOrNull(), status, q, filters, sortKey, boundedLimit, cursor);
            return ResponseEntity.ok(Map.of(
                    "items", page.items().stream().map(JobsController::toWire).toList(),
                    "next_cursor", page.nextCursor() != null ? page.nextCursor() : ""));
        } catch (JobRepository.CursorMismatchException e) {
            return badRequest(httpRequest, "Invalid cursor", e.getMessage());
        } catch (IllegalArgumentException e) {
            // Base64.getDecoder().decode on a malformed cursor string.
            return badRequest(httpRequest, "Invalid cursor", "cursor is not well-formed");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
     *
     * <p>Source hiding (Phase 8.2.5): this is a CANDIDATE view, so nothing that
     * reveals which website, ATS platform or aggregator surfaced the posting is
     * included — no source id/name/kind, no source external id and no
     * canonical (discovery) URL. Provenance stays in the database
     * ({@code jobs.source_id}, {@code job_source_observations}) and in the
     * operational Sources views. The employer and the employer's genuine
     * application URL are still shown; nothing is replaced or disguised.
     */
    static Map<String, Object> toWire(JobRepository.FeedItem item) {
        JobRecord job = item.job();
        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("id", job.id());
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
        wire.put("application_url", candidateApplicationUrl(job.applicationUrl()));
        wire.put("posted_at", job.postedAt() == null ? null : job.postedAt().toString());
        wire.put("status", job.status());
        wire.put("first_seen_at", item.firstSeenAt().toString());
        wire.put("last_seen_at", item.lastSeenAt().toString());
        wire.put("stale", item.stale());
        wire.put("removed", item.removed());
        wire.put("match_score", item.matchScore());
        wire.put("match_recommendation", item.matchRecommendation());
        wire.put("match_breakdown", item.matchBreakdown());
        return wire;
    }

    /** Aggregator pages are never presented as the employer's application page. */
    static String candidateApplicationUrl(String url) {
        return com.personal.jobagent.discovery.platform.JobIdentity.isAggregatorUrl(url) ? null : url;
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

    /**
     * @param platform optional platform name/kind when the URL alone is not enough
     * @param board    optional board identifier in the platform's format
     * @param preview  list one page of the resolved board so it can be inspected
     * @param confirm  create the source and run it once
     */
    public record ImportUrlRequest(String url, String platform, String board, Boolean preview, Boolean confirm,
                                   String employer_name) {
        public ImportUrlRequest(String url) {
            this(url, null, null, null, null, null);
        }
    }

    /**
     * Resolves a careers/job URL to a supported board (SSRF-safe, see
     * {@link com.personal.jobagent.discovery.platform.BoardImportService}),
     * optionally previews it, and — only when confirmed — configures it as a
     * discovery source and runs it once. Malformed or unsafe URLs are 400.
     */
    @PostMapping("/import-url")
    public ResponseEntity<?> importUrl(@RequestBody ImportUrlRequest request, HttpServletRequest httpRequest) {
        String raw = request == null ? null : request.url();
        try {
            if (raw == null) throw new URISyntaxException("", "missing");
            new URI(raw.strip()).toURL();
        } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
            return badRequest(httpRequest, "Invalid URL", "url must be well-formed");
        }
        if (boardImport == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "RESOLUTION_PENDING"));
        }
        try {
            return ResponseEntity.ok(boardImport.importUrl(new com.personal.jobagent.discovery.platform.BoardImportService.ImportRequest(
                    raw, request.platform(), request.board(), request.preview(), request.confirm(), request.employer_name())));
        } catch (com.personal.jobagent.discovery.platform.BoardImportService.ImportRejected e) {
            if (e.status() == 429) {
                return ResponseEntity.status(429).body(ApiError.of(429, "Too Many Requests", e.getMessage(),
                        httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id"))));
            }
            return badRequest(httpRequest, "Invalid URL", e.getMessage());
        } catch (com.personal.jobagent.discovery.platform.BoardDiscoveryService.RunRefused e) {
            return badRequest(httpRequest, "Import refused", e.getMessage());
        }
    }

    private static ResponseEntity<?> badRequest(HttpServletRequest httpRequest, String title, String detail) {
        return ResponseEntity.badRequest().body(ApiError.of(400, title, detail, httpRequest.getRequestURI(),
                String.valueOf(org.slf4j.MDC.get("correlation_id"))));
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
