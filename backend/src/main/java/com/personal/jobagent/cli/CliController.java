package com.personal.jobagent.cli;

import com.personal.jobagent.ats.AtsAdapter;
import com.personal.jobagent.ats.AtsAdapterRegistry;
import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.qa.ApplicationAnswerRecord;
import com.personal.jobagent.qa.ApplicationAnswerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * CLI-friendly REST API layer.
 *
 * All endpoints return plain-text or simplified JSON designed to be consumed
 * by the bundled cli.sh / cli.ps1 wrapper scripts.
 *
 * Endpoints:
 *   GET  /cli/jobs                           - list jobs
 *   GET  /cli/jobs/{id}                      - show job
 *   POST /cli/cover-letter/{jobId}           - generate cover letter
 *   POST /cli/answer/{jobId}?q=...           - draft application answer
 *   GET  /cli/ats                            - list ATS adapters
 *   GET  /cli/status                         - service health summary
 */
@RestController
@RequestMapping("/cli")
public class CliController {

    private static final UUID CLI_PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CLI_APPLICATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    private final JobRepository jobRepository;
    private final CoverLetterService coverLetterService;
    private final ApplicationAnswerService answerService;
    private final AtsAdapterRegistry atsAdapterRegistry;

    public CliController(JobRepository jobRepository,
                         CoverLetterService coverLetterService,
                         ApplicationAnswerService answerService,
                         AtsAdapterRegistry atsAdapterRegistry) {
        this.jobRepository = jobRepository;
        this.coverLetterService = coverLetterService;
        this.answerService = answerService;
        this.atsAdapterRegistry = atsAdapterRegistry;
    }

    /** GET /cli/jobs?status=DISCOVERED&q=engineer&limit=10 */
    @GetMapping("/jobs")
    public ResponseEntity<?> listJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit) {

        limit = Math.min(limit, 100);
        JobRepository.Page page = jobRepository.findJobs(status, q, limit, null);

        List<Map<String, Object>> rows = page.items().stream().map(job -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", job.id());
            row.put("title", job.title());
            row.put("company", job.companyNameRaw());
            row.put("location", job.locationRaw());
            row.put("status", job.status());
            row.put("remote_type", job.remoteType());
            return row;
        }).toList();

        return ResponseEntity.ok(Map.of("count", rows.size(), "jobs", rows));
    }

    /** GET /cli/jobs/{id} */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> showJob(@PathVariable UUID id) {
        return jobRepository.findById(id)
                .map(job -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("id", job.id());
                    detail.put("title", job.title());
                    detail.put("company", job.companyNameRaw());
                    detail.put("location", job.locationRaw());
                    detail.put("remote_type", job.remoteType());
                    detail.put("employment_type", job.employmentType());
                    detail.put("salary_min", job.salaryMin());
                    detail.put("salary_max", job.salaryMax());
                    detail.put("salary_currency", job.salaryCurrency());
                    detail.put("status", job.status());
                    detail.put("application_url", job.applicationUrl());
                    detail.put("description_preview",
                            job.descriptionText() != null && job.descriptionText().length() > 500
                                    ? job.descriptionText().substring(0, 500) + "..."
                                    : job.descriptionText());
                    return ResponseEntity.ok((Object) detail);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** POST /cli/cover-letter/{jobId} */
    @PostMapping("/cover-letter/{jobId}")
    public ResponseEntity<?> generateCoverLetter(@PathVariable UUID jobId) {
        try {
            CoverLetterService.GenerationResult result = coverLetterService.generateCoverLetter(
                    CLI_PROFILE_ID, jobId, CLI_APPLICATION_ID);
            CoverLetterRecord cl = result.coverLetter();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", cl.id());
            resp.put("title", cl.title());
            resp.put("is_approved", cl.isApproved());
            resp.put("passed_validation", result.passedValidation());
            resp.put("issues", result.issues());
            resp.put("body_markdown", cl.bodyMarkdown());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /cli/answer/{jobId}?q=Why+do+you+want+this+role */
    @PostMapping("/answer/{jobId}")
    public ResponseEntity<?> draftAnswer(@PathVariable UUID jobId,
                                          @RequestParam("q") String question) {
        try {
            ApplicationAnswerService.AnswerResult result = answerService.draftAnswer(
                    CLI_PROFILE_ID, jobId, CLI_APPLICATION_ID, question);
            ApplicationAnswerRecord rec = result.record();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", rec.id());
            resp.put("question", rec.questionText());
            resp.put("question_type", rec.questionType());
            resp.put("status", rec.status());
            resp.put("confidence", rec.confidence());
            resp.put("answer_text", rec.answerText());
            resp.put("needs_user_input", "NEEDS_USER_INPUT".equals(rec.status()));
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /cli/ats */
    @GetMapping("/ats")
    public ResponseEntity<?> listAtsAdapters() {
        List<AtsAdapter> adapters = atsAdapterRegistry.getAdapters();
        List<Map<String, Object>> rows = adapters.stream()
                .map(a -> Map.<String, Object>of("kind", a.kind().name()))
                .toList();
        return ResponseEntity.ok(Map.of("count", rows.size(), "adapters", rows));
    }

    /** GET /cli/status */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        long jobCount = 0;
        try {
            JobRepository.Page page = jobRepository.findJobs(null, null, 1, null);
            // Just check connectivity — we don't need full count here
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "DEGRADED", "error", e.getMessage()));
        }

        int adapterCount = atsAdapterRegistry.getAdapters().size();
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "ats_adapters_loaded", adapterCount,
                "api_prefix", "/api/v1",
                "mcp_endpoint", "/mcp",
                "cli_endpoint", "/cli"
        ));
    }
}