package com.personal.jobagent.prep;

import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.qa.ApplicationAnswerRecord;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import com.personal.jobagent.resume.ResumeAtsAnalysis;
import com.personal.jobagent.resume.ResumeAtsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Preparation readiness for one job, derived ONLY from records that exist:
 * the owner's tailored CV version (its validation, review and stored PDF
 * digest), cover-letter versions (validation, approval, body digest) and
 * drafted answers (status and human confirmation).
 *
 * <p>No preparation status is stored anywhere. In particular the
 * application status READY_TO_APPLY is ignored: it is set when an application
 * is created, before any document exists. ATS-specific required questions are
 * not known for any job yet, so they are reported as "not checked" rather than
 * passed.
 */
@Service
public class PrepReadinessService {

    public enum Overall { NOT_STARTED, BLOCKED, IN_PROGRESS, READY_FOR_REVIEW }

    private final JdbcTemplate jdbc;
    private final JobRepository jobs;
    private final ResumeAtsRepository cvs;
    private final CoverLetterRepository letters;
    private final ApplicationAnswerRepository answers;

    public PrepReadinessService(JdbcTemplate jdbc, JobRepository jobs, ResumeAtsRepository cvs,
                                CoverLetterRepository letters, ApplicationAnswerRepository answers) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.cvs = cvs;
        this.letters = letters;
        this.answers = answers;
    }

    private record App(UUID id, String status, UUID cvVersionId) {}

    /**
     * @throws NoSuchElementException when the job does not exist or the given
     *         application is not the caller's application for this job
     */
    public Map<String, Object> readiness(UUID profileId, UUID jobId, UUID applicationId) {
        if (jobs.findById(jobId).isEmpty()) throw new NoSuchElementException("Job not found");
        App app = application(profileId, jobId, applicationId);

        List<Map<String, Object>> blockers = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        List<String> notChecked = new ArrayList<>();

        Map<String, Object> cv = cvSection(profileId, jobId, app, blockers, actions);
        Map<String, Object> letter = letterSection(profileId, jobId, app, blockers, actions);
        Map<String, Object> answerSection = answerSection(profileId, jobId, app, blockers, actions);
        notChecked.add("ATS-specific required questions: not available for this job, so required screening answers have not been checked.");
        notChecked.add("Whether this employer requires a cover letter: unknown, so a letter is treated as optional.");

        boolean nothing = "MISSING".equals(cv.get("state")) && "NOT_GENERATED".equals(letter.get("state"))
                && ((Number) answerSection.get("total")).intValue() == 0;
        Overall overall = nothing ? Overall.NOT_STARTED
                : !blockers.isEmpty() ? Overall.BLOCKED
                : !actions.isEmpty() ? Overall.IN_PROGRESS
                : Overall.READY_FOR_REVIEW;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("applicationId", app == null ? null : app.id());
        out.put("applicationStatus", app == null ? null : app.status());
        out.put("overall", overall.name());
        out.put("overallLabel", switch (overall) {
            case NOT_STARTED -> "Not started";
            case BLOCKED -> "Blocked";
            case IN_PROGRESS -> "In progress";
            case READY_FOR_REVIEW -> "Ready for the next human-review step";
        });
        out.put("cv", cv);
        out.put("coverLetter", letter);
        out.put("answers", answerSection);
        out.put("blockers", blockers);
        out.put("actions", actions);
        out.put("notChecked", notChecked);
        out.put("note", "Derived from your stored CV, cover-letter and answer records. The application status "
                + "READY_TO_APPLY is set when an application is created and is not evidence of preparation. "
                + "Nothing here submits an application.");
        out.put("computedAt", Instant.now().toString());
        return out;
    }

    private App application(UUID profileId, UUID jobId, UUID applicationId) {
        if (applicationId != null) {
            return jdbc.query("select id, status, cv_version_id from applications where id = ? and profile_id = ? and job_id = ?",
                            (rs, n) -> new App((UUID) rs.getObject("id"), rs.getString("status"), (UUID) rs.getObject("cv_version_id")),
                            applicationId, profileId, jobId)
                    .stream().findFirst().orElseThrow(() -> new NoSuchElementException("Application not found for this job"));
        }
        return jdbc.query("select id, status, cv_version_id from applications where profile_id = ? and job_id = ? "
                                + "order by created_at desc, id desc limit 1",
                        (rs, n) -> new App((UUID) rs.getObject("id"), rs.getString("status"), (UUID) rs.getObject("cv_version_id")),
                        profileId, jobId)
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> cvSection(UUID profileId, UUID jobId, App app,
                                          List<Map<String, Object>> blockers, List<Map<String, Object>> actions) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<ResumeAtsAnalysis> cv = Optional.empty();
        if (app != null && app.cvVersionId() != null) cv = cvs.findByCvVersion(profileId, app.cvVersionId());
        if (cv.isEmpty() && app != null) cv = cvs.findLatest(profileId, jobId, app.id());
        if (cv.isEmpty()) cv = cvs.findLatest(profileId, jobId, null);
        if (cv.isEmpty()) {
            out.put("state", "MISSING");
            actions.add(item("CV", "CV_MISSING", "Generate a tailored CV for this job."));
            return out;
        }
        ResumeAtsAnalysis a = cv.get();
        out.put("cvVersionId", a.cvVersionId());
        out.put("profileRevision", a.profileRevision());
        boolean linked = app != null && a.cvVersionId().equals(app.cvVersionId());
        out.put("linkedToApplication", app == null ? null : linked);

        var check = cvs.checkedArtifact(profileId, a.cvVersionId());
        boolean intact = check.map(ResumeAtsRepository.ArtifactCheck::intact).orElse(false);
        out.put("artifactIntact", intact);
        out.put("artifactSha256", check.map(ResumeAtsRepository.ArtifactCheck::actualSha256).orElse(null));

        Object validation = a.atsReport() == null ? null : a.atsReport().get("validation");
        var review = cvs.review(profileId, a.cvVersionId());
        boolean approved = review.isPresent() && review.get().approved()
                && check.isPresent() && Objects.equals(review.get().contentSha256(), check.get().actualSha256());
        out.put("approved", approved);

        String state;
        if (!intact) {
            state = "INTEGRITY_FAILED";
            blockers.add(item("CV", "CV_ARTIFACT_INTEGRITY", "The stored CV PDF is missing or does not match its recorded checksum. Regenerate the CV."));
        } else if (!(validation instanceof Map<?, ?> v)) {
            state = "LEGACY_UNVALIDATED";
            blockers.add(item("CV", "CV_NOT_VALIDATED", "This CV was generated before the current checks and PDF renderer. Regenerate it."));
        } else if (!Boolean.TRUE.equals(v.get("passed"))) {
            state = "VALIDATION_BLOCKED";
            blockers.add(item("CV", "CV_VALIDATION_BLOCKED", "The CV has blocking validation findings. Correct your profile and regenerate."));
        } else if (approved) {
            state = "APPROVED";
        } else {
            state = "AWAITING_REVIEW";
            actions.add(item("CV", "CV_REVIEW", "Review the tailored CV and approve it."));
        }
        if (app != null && !linked && !"INTEGRITY_FAILED".equals(state)) {
            actions.add(item("CV", "CV_NOT_LINKED", "Generate the CV for your application so that version is attached to it."));
        }
        out.put("state", state);
        if (validation instanceof Map<?, ?> v) out.put("validation", v);
        return out;
    }

    private Map<String, Object> letterSection(UUID profileId, UUID jobId, App app,
                                              List<Map<String, Object>> blockers, List<Map<String, Object>> actions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requirement", "UNKNOWN");
        List<CoverLetterRecord> all = letters.findByJobIdForProfile(jobId, profileId);
        Optional<CoverLetterRecord> chosen = app == null ? all.stream().findFirst()
                : all.stream().filter(l -> app.id().equals(l.applicationId())).findFirst().or(() -> all.stream().findFirst());
        out.put("versions", all.size());
        if (chosen.isEmpty()) {
            out.put("state", "NOT_GENERATED");
            return out;
        }
        CoverLetterRecord l = chosen.get();
        out.put("coverLetterId", l.id());
        out.put("version", l.version());
        out.put("origin", l.origin());
        out.put("approved", l.isApproved());
        out.put("linkedToApplication", app == null ? null : app.id().equals(l.applicationId()));
        boolean intact = CoverLetterService.bodyIntact(l);
        Map<String, Object> validation = l.claimsValidation() == null ? Map.of() : l.claimsValidation();
        boolean current = validation.containsKey("validator_version");
        boolean passed = Boolean.TRUE.equals(validation.get("passed"));
        out.put("validatedByCurrentChecks", current);

        String state;
        if (!intact) {
            state = "INTEGRITY_FAILED";
            blockers.add(item("COVER_LETTER", "LETTER_INTEGRITY", "The latest cover letter no longer matches its recorded digest."));
        } else if (current && !passed) {
            state = "VALIDATION_BLOCKED";
            blockers.add(item("COVER_LETTER", "LETTER_VALIDATION_BLOCKED",
                    "The latest cover letter (v" + l.version() + ") has blocking validation findings. Submit a correction."));
        } else if (l.isApproved() && current) {
            state = "APPROVED";
        } else if (l.isApproved()) {
            state = "APPROVED_UNDER_OLD_CHECKS";
            actions.add(item("COVER_LETTER", "LETTER_REAPPROVE",
                    "The latest cover letter was approved before the current checks existed. Approve it again so it is re-validated."));
        } else {
            state = "AWAITING_APPROVAL";
            actions.add(item("COVER_LETTER", "LETTER_REVIEW", "Read the cover letter (v" + l.version() + ") and approve it, or correct it."));
        }
        out.put("state", state);
        return out;
    }

    private Map<String, Object> answerSection(UUID profileId, UUID jobId, App app,
                                              List<Map<String, Object>> blockers, List<Map<String, Object>> actions) {
        List<ApplicationAnswerRecord> all = answers.findByJobIdForProfile(jobId, profileId);
        int needsInput = 0, unconfirmed = 0, confirmed = 0;
        for (ApplicationAnswerRecord a : all) {
            if (!"ANSWERED".equals(a.status())) {
                needsInput++;
                blockers.add(item("ANSWERS", "ANSWER_NEEDS_INPUT",
                        "\"" + a.questionText() + "\" needs your input (" + a.status() + ")."));
            } else if (!a.humanConfirmed()) {
                unconfirmed++;
                actions.add(item("ANSWERS", "ANSWER_UNCONFIRMED", "Review and confirm the answer to \"" + a.questionText() + "\"."));
            } else {
                confirmed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("confirmed", confirmed);
        out.put("unconfirmed", unconfirmed);
        out.put("needsInput", needsInput);
        out.put("requiredQuestionsKnown", false);
        return out;
    }

    private static Map<String, Object> item(String area, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("area", area);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
