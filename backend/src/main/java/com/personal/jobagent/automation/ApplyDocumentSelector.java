package com.personal.jobagent.automation;

import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement;
import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.resume.ResumeAtsAnalysis;
import com.personal.jobagent.resume.ResumeAtsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fail-closed selection of the EXACT, immutable document versions an
 * execution package may carry (Phase 8.2).
 *
 * <p>The defect this replaces: the package attached the latest cover letter
 * regardless of approval, and the CV was taken from the application link
 * without checking that anyone had reviewed the version whose bytes ship.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Binding.</b> A CV is only ever a version generated for THIS
 *       application (user, job and application must all match). A cover
 *       letter is only ever a version linked to THIS application. Nothing is
 *       pulled in from other records and nothing is invented.</li>
 *   <li><b>Exact, reviewed versions.</b> The selected CV must carry an owner
 *       review that is approved and bound to the version's CURRENT content
 *       digest. The selected cover letter must be explicitly approved — the
 *       designated version is the newest approved one.</li>
 *   <li><b>Integrity.</b> Stored PDF bytes must match the recorded SHA-256
 *       and a letter body must match its generation-time digest. Any mismatch
 *       means the bytes are not what was reviewed.</li>
 *   <li><b>Current validation.</b> A document validated by an older
 *       validator (or never validated) is rejected.</li>
 *   <li><b>No silent fallback.</b> A newer version that is not yet reviewed
 *       or approved never displaces the reviewed/approved version (that would
 *       silently replace what the user actually reviewed); the situation is
 *       reported as a warning instead. When the designated version FAILS its
 *       checks, nothing is substituted — no newest, no previous — the package
 *       is blocked (or the letter is simply not used, per the employer
 *       requirement) with the exact reason.</li>
 *   <li><b>Unknown requirements stay unknown.</b> Whether the employer
 *       requires a cover letter comes only from the captured application
 *       form; absent that evidence it is UNKNOWN and no requirement is
 *       invented.</li>
 * </ul>
 */
@Service
public class ApplyDocumentSelector {

    public record SelectedCv(UUID versionId, String pdfSha256, long byteSize, String reviewDecidedAt) {}

    public record SelectedCoverLetter(UUID versionId, int version, String origin,
                                      String bodySha256, String pdfSha256, boolean pdfStored) {}

    private record Rejection(String code, String message) {}

    public record DocumentSelection(SelectedCv cv, SelectedCoverLetter coverLetter,
                                    CoverLetterRequirement coverLetterRequirement,
                                    List<Map<String, Object>> warnings,
                                    List<Map<String, Object>> blockers) {
        public boolean blocked() {
            return !blockers.isEmpty();
        }
    }

    private final ResumeAtsRepository cvs;
    private final CoverLetterRepository letters;
    private final JobFormQuestionService formQuestions;

    public ApplyDocumentSelector(ResumeAtsRepository cvs, CoverLetterRepository letters,
                                 JobFormQuestionService formQuestions) {
        this.cvs = cvs;
        this.letters = letters;
        this.formQuestions = formQuestions;
    }

    public DocumentSelection select(UUID profileId, UUID jobId, UUID applicationId) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> blockers = new ArrayList<>();
        SelectedCv cv = selectCv(profileId, jobId, applicationId, warnings, blockers);
        CoverLetterRequirement requirement = formQuestions.coverLetterRequirement(jobId);
        SelectedCoverLetter letter = selectCoverLetter(profileId, jobId, applicationId, requirement, warnings, blockers);
        return new DocumentSelection(cv, letter, requirement, List.copyOf(warnings), List.copyOf(blockers));
    }

    // ── CV ───────────────────────────────────────────────────────────

    private SelectedCv selectCv(UUID profileId, UUID jobId, UUID applicationId,
                                List<Map<String, Object>> warnings, List<Map<String, Object>> blockers) {
        List<ResumeAtsAnalysis> candidates = cvs.findForApplication(profileId, jobId, applicationId);
        if (candidates.isEmpty()) {
            blockers.add(item("CV", "CV_MISSING",
                    "No tailored CV exists for this application. A CV must be generated and reviewed before an "
                            + "execution package can be built."));
            return null;
        }
        // Newest first. The first version that passes every check is the
        // selection; a newer failing version never displaces it (reported as a
        // warning) and when nothing passes nothing is substituted.
        SelectedCv chosen = null;
        List<Rejection> rejectionsBefore = new ArrayList<>();
        List<Rejection> rejectionsAfter = new ArrayList<>();
        for (ResumeAtsAnalysis candidate : candidates) {
            Rejection rejection = cvRejection(profileId, candidate);
            if (rejection != null) {
                (chosen == null ? rejectionsBefore : rejectionsAfter).add(rejection);
                continue;
            }
            if (chosen == null) {
                var check = cvs.checkedArtifact(profileId, candidate.cvVersionId()).orElseThrow();
                var review = cvs.review(profileId, candidate.cvVersionId()).orElseThrow();
                chosen = new SelectedCv(candidate.cvVersionId(), check.actualSha256(),
                        check.bytes().length, review.decidedAt() == null ? null : review.decidedAt().toString());
            }
        }
        if (chosen == null) {
            for (Rejection rejection : rejectionsBefore) {
                blockers.add(item("CV", rejection.code(), rejection.message()));
            }
            return null;
        }
        for (Rejection rejection : rejectionsBefore) {
            warnings.add(item("CV", "NEWER_CV_NOT_USED",
                    "A newer CV version exists but is not usable, so the reviewed version is used instead. "
                            + "Review the replacement to use it. " + rejection.message()));
        }
        return chosen;
    }

    /** Rejection for one CV version, or null when it qualifies. */
    private Rejection cvRejection(UUID profileId, ResumeAtsAnalysis candidate) {
        String prefix = "CV " + candidate.cvVersionId() + ": ";
        var check = cvs.checkedArtifact(profileId, candidate.cvVersionId());
        if (check.isEmpty() || check.get().bytes() == null) {
            return new Rejection("CV_ARTIFACT_MISSING", prefix + "the stored PDF is missing.");
        }
        if (!check.get().intact()) {
            return new Rejection("CV_ARTIFACT_INTEGRITY_FAILED",
                    prefix + "the stored PDF does not match its recorded checksum (integrity failure).");
        }
        Object validation = candidate.atsReport() == null ? null : candidate.atsReport().get("validation");
        if (!(validation instanceof Map<?, ?> report)) {
            return new Rejection("CV_LEGACY_UNVALIDATED",
                    prefix + "this CV was generated before the current validation checks (legacy, unvalidated).");
        }
        if (!DocumentFactValidator.VERSION.equals(report.get("validator_version"))) {
            return new Rejection("CV_STALE_VALIDATION",
                    prefix + "its validation result was produced by an older validator.");
        }
        if (!Boolean.TRUE.equals(report.get("passed"))) {
            return new Rejection("CV_VALIDATION_BLOCKED", prefix + "the CV has blocking validation findings.");
        }
        var review = cvs.review(profileId, candidate.cvVersionId());
        if (review.isEmpty() || !review.get().approved()) {
            return new Rejection("CV_REVIEW_REQUIRED",
                    prefix + "this CV version has not been reviewed and approved.");
        }
        if (!review.get().contentSha256().equals(candidate.contentSha256())
                || !review.get().contentSha256().equals(check.get().actualSha256())) {
            return new Rejection("CV_REVIEW_DIGEST_MISMATCH",
                    prefix + "the review is not bound to the CV's current content digest.");
        }
        return null;
    }

    // ── cover letter ─────────────────────────────────────────────────

    private SelectedCoverLetter selectCoverLetter(UUID profileId, UUID jobId, UUID applicationId,
                                                  CoverLetterRequirement requirement,
                                                  List<Map<String, Object>> warnings,
                                                  List<Map<String, Object>> blockers) {
        List<CoverLetterRecord> linked = letters.findByJobIdForProfile(jobId, profileId).stream()
                .filter(letter -> applicationId.equals(letter.applicationId()))
                .toList(); // repository returns newest version first
        if (linked.isEmpty()) {
            if (requirement == CoverLetterRequirement.REQUIRED) {
                blockers.add(item("COVER_LETTER", "COVER_LETTER_REQUIRED_MISSING",
                        "This employer's application form requires a cover letter and none is attached to "
                                + "this application."));
            } else if (requirement == CoverLetterRequirement.UNKNOWN) {
                warnings.add(item("COVER_LETTER", "COVER_LETTER_REQUIREMENT_UNKNOWN",
                        "No cover letter is attached to this application, and this employer's form does not state "
                                + "whether one is required. Completeness cannot be claimed."));
            }
            return null;
        }
        // The designated version is the NEWEST APPROVED one: an unapproved
        // draft never ships, and approval is what says "this is the version I
        // reviewed". A newer unapproved version does not displace it.
        CoverLetterRecord designated = linked.stream().filter(CoverLetterRecord::isApproved).findFirst().orElse(null);
        for (CoverLetterRecord letter : linked) {
            if (letter.equals(designated)) break;
            warnings.add(item("COVER_LETTER", "NEWER_COVER_LETTER_NOT_USED",
                    "Cover letter v" + letter.version() + " exists but is not approved, so it is not used. "
                            + "Approve it to make it the version in the package."));
        }
        if (designated == null) {
            CoverLetterRecord newest = linked.get(0);
            // Nothing is substituted: no newest, no previous.
            if (requirement == CoverLetterRequirement.REQUIRED) {
                blockers.add(item("COVER_LETTER", "COVER_LETTER_NOT_APPROVED",
                        "Cover letter v" + newest.version() + " has not been approved."));
            } else {
                warnings.add(item("COVER_LETTER", "COVER_LETTER_NOT_APPROVED",
                        "Cover letter v" + newest.version() + " has not been approved. It is not included in the package."));
            }
            return null;
        }
        Rejection rejection = letterRejection(profileId, designated);
        if (rejection != null) {
            // The designated version fails its checks. No other version is
            // substituted; a required letter makes this a hard blocker, and an
            // integrity failure is always a hard blocker.
            Map<String, Object> entry = item("COVER_LETTER", rejection.code(), rejection.message());
            if (requirement == CoverLetterRequirement.REQUIRED || rejection.code().contains("INTEGRITY")) {
                blockers.add(entry);
            } else {
                warnings.add(entry);
            }
            return null;
        }
        var pdf = designated.pdfStored() ? letters.pdfForProfile(designated.id(), profileId).orElse(null) : null;
        return new SelectedCoverLetter(designated.id(), designated.version(), designated.origin(),
                designated.contentSha256(), pdf == null ? null : pdf.actualSha256(), designated.pdfStored());
    }

    /** Rejection for the designated letter version, or null when it qualifies. */
    private Rejection letterRejection(UUID profileId, CoverLetterRecord letter) {
        String prefix = "Cover letter v" + letter.version() + ": ";
        if (!CoverLetterService.bodyIntact(letter)) {
            return new Rejection("COVER_LETTER_INTEGRITY_FAILED",
                    prefix + "the stored content no longer matches its recorded digest (integrity failure).");
        }
        if (letter.pdfStored()) {
            var pdf = letters.pdfForProfile(letter.id(), profileId).orElse(null);
            if (pdf == null || !pdf.intact()) {
                return new Rejection("COVER_LETTER_PDF_INTEGRITY_FAILED",
                        prefix + "the rendered PDF is missing or does not match its recorded digest (integrity failure).");
            }
        }
        Map<String, Object> validation = letter.claimsValidation() == null ? Map.of() : letter.claimsValidation();
        if (!DocumentFactValidator.VERSION.equals(validation.get("validator_version"))) {
            return new Rejection("COVER_LETTER_STALE_VALIDATION", prefix + (validation.containsKey("validator_version")
                    ? "its validation result was produced by an older validator."
                    : "it has no current validation result (approved before the current checks existed)."));
        }
        if (!Boolean.TRUE.equals(validation.get("passed"))) {
            return new Rejection("COVER_LETTER_VALIDATION_BLOCKED", prefix + "it has blocking validation findings.");
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> item(String area, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("area", area);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
