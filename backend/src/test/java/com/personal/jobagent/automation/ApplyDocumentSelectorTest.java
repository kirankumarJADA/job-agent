package com.personal.jobagent.automation;

import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement;
import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.resume.ResumeAtsAnalysis;
import com.personal.jobagent.resume.ResumeAtsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 8.2: fail-closed document selection. Every case proves the negative —
 * an unreviewed, unapproved, stale-validated, tampered or mismatched document
 * is never selected and never substituted.
 */
class ApplyDocumentSelectorTest {

    private final UUID profile = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();

    private ResumeAtsRepository cvs;
    private CoverLetterRepository letters;
    private JobFormQuestionService formQuestions;
    private ApplyDocumentSelector selector;

    @BeforeEach
    void setUp() {
        cvs = Mockito.mock(ResumeAtsRepository.class);
        letters = Mockito.mock(CoverLetterRepository.class);
        formQuestions = Mockito.mock(JobFormQuestionService.class);
        selector = new ApplyDocumentSelector(cvs, letters, formQuestions);
        when(formQuestions.coverLetterRequirement(job)).thenReturn(CoverLetterRequirement.UNKNOWN);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of());
        // Default posture: one reviewed, validated, intact CV — letter tests
        // override the letter side, CV tests override this stub.
        stubCv(UUID.randomUUID(), "cv-digest", true, currentValidation(true), review(true, "cv-digest"));
    }

    // ── CV ───────────────────────────────────────────────────────────

    @Test
    void aReviewedValidatedIntactCvIsSelectedExactly() {
        UUID cvId = UUID.randomUUID();
        stubCv(cvId, "cv-digest", true, currentValidation(true), review(true, "cv-digest"));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isFalse();
        assertThat(selection.cv().versionId()).isEqualTo(cvId);
        assertThat(selection.cv().pdfSha256()).isEqualTo("cv-digest");
    }

    @Test
    void anUnreviewedCvBlocksThePackage() {
        stubCv(UUID.randomUUID(), "cv-digest", true, currentValidation(true), Optional.empty());

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.cv()).isNull();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("CV_REVIEW_REQUIRED");
    }

    @Test
    void aReviewBoundToADifferentDigestBlocksThePackage() {
        stubCv(UUID.randomUUID(), "cv-digest", true, currentValidation(true), review(true, "other-digest"));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("CV_REVIEW_DIGEST_MISMATCH");
    }

    @Test
    void aLegacyUnvalidatedCvBlocksThePackage() {
        // A CV generated before the current checks: its report has no
        // validation section at all.
        stubCv(UUID.randomUUID(), "cv-digest", true, null, review(true, "cv-digest"));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("CV_LEGACY_UNVALIDATED");
    }

    @Test
    void anIntegrityFailedCvBlocksThePackage() {
        stubCv(UUID.randomUUID(), "cv-digest", false, currentValidation(true), review(true, "cv-digest"));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("CV_ARTIFACT_INTEGRITY_FAILED");
    }

    @Test
    void aValidationBlockedCvBlocksThePackage() {
        stubCv(UUID.randomUUID(), "cv-digest", true, currentValidation(false), review(true, "cv-digest"));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("CV_VALIDATION_BLOCKED");
    }

    @Test
    void aMissingCvBlocksThePackage() {
        when(cvs.findForApplication(profile, job, application)).thenReturn(List.<ResumeAtsAnalysis>of());

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("CV_MISSING");
    }

    @Test
    void aNewerUnreviewedCvDoesNotReplaceTheReviewedOneButIsReported() {
        UUID reviewedId = UUID.randomUUID();
        UUID newerId = UUID.randomUUID();
        when(cvs.findForApplication(profile, job, application)).thenReturn(List.of(
                cvAnalysis(newerId, "newer-digest"),
                cvAnalysis(reviewedId, "cv-digest")));
        when(cvs.checkedArtifact(profile, newerId)).thenReturn(Optional.of(artifact("newer-digest", true)));
        when(cvs.checkedArtifact(profile, reviewedId)).thenReturn(Optional.of(artifact("cv-digest", true)));
        when(cvs.review(profile, newerId)).thenReturn(Optional.empty());
        when(cvs.review(profile, reviewedId)).thenReturn(review(true, "cv-digest"));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isFalse();
        assertThat(selection.cv().versionId()).isEqualTo(reviewedId);
        assertThat(selection.warnings()).extracting(w -> w.get("code")).contains("NEWER_CV_NOT_USED");
    }

    // ── cover letter ─────────────────────────────────────────────────

    @Test
    void anUnapprovedLetterIsNeverSelected() {
        when(formQuestions.coverLetterRequirement(job)).thenReturn(CoverLetterRequirement.UNKNOWN);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(letter(2, "v2 text", false)));

        var selection = selector.select(profile, job, application);

        assertThat(selection.coverLetter()).isNull();
        assertThat(selection.warnings()).extracting(w -> w.get("code")).contains("COVER_LETTER_NOT_APPROVED");
    }

    @Test
    void anUnapprovedRequiredLetterIsAHardBlocker() {
        when(formQuestions.coverLetterRequirement(job)).thenReturn(CoverLetterRequirement.REQUIRED);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(letter(1, "v1 text", false)));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("COVER_LETTER_NOT_APPROVED");
    }

    @Test
    void aLetterWithOnlyAnOldValidationResultIsRejected() {
        CoverLetterRecord stale = new CoverLetterRecord(UUID.randomUUID(), profile, job, application, 1, "v1",
                "v1 text", Map.of("passed", true), true, Instant.now(), Instant.now(),
                ExecutionPackageService.Sha256.of("v1 text"), null, "GENERATED", false);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(stale));

        var selection = selector.select(profile, job, application);

        assertThat(selection.coverLetter()).isNull();
        assertThat(selection.warnings()).extracting(w -> w.get("code")).contains("COVER_LETTER_STALE_VALIDATION");
    }

    @Test
    void alteredLetterContentIsRejectedAsAnIntegrityFailure() {
        CoverLetterRecord tampered = new CoverLetterRecord(UUID.randomUUID(), profile, job, application, 1, "v1",
                "edited after approval", currentLetterValidation(), true, Instant.now(), Instant.now(),
                ExecutionPackageService.Sha256.of("original text"), null, "GENERATED", false);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(tampered));

        var selection = selector.select(profile, job, application);

        assertThat(selection.coverLetter()).isNull();
        // Integrity failures are always hard blockers, whatever the requirement.
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("COVER_LETTER_INTEGRITY_FAILED");
    }

    @Test
    void noSilentFallbackToThePreviousLetterWhenTheApprovedOneFails() {
        CoverLetterRecord broken = new CoverLetterRecord(UUID.randomUUID(), profile, job, application, 2, "v2",
                "v2 text", Map.of("passed", false, "validator_version", DocumentFactValidator.VERSION),
                true, Instant.now(), Instant.now(), ExecutionPackageService.Sha256.of("v2 text"), null, "GENERATED", false);
        CoverLetterRecord older = letter(1, "v1 text", true);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(broken, older));

        var selection = selector.select(profile, job, application);

        // The designated (approved) version failed; the previous one is NOT substituted.
        assertThat(selection.coverLetter()).isNull();
    }

    @Test
    void aNewerUnapprovedLetterDoesNotDisplaceTheApprovedVersionButIsReported() {
        CoverLetterRecord unapproved = letter(3, "v3 text", false);
        CoverLetterRecord approved = letter(2, "v2 text", true);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(unapproved, approved));

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isFalse();
        assertThat(selection.coverLetter().versionId()).isEqualTo(approved.id());
        assertThat(selection.coverLetter().version()).isEqualTo(2);
        assertThat(selection.warnings()).extracting(w -> w.get("code")).contains("NEWER_COVER_LETTER_NOT_USED");
    }

    @Test
    void aLetterForAnotherApplicationIsNeverSelected() {
        CoverLetterRecord foreign = new CoverLetterRecord(UUID.randomUUID(), profile, job, UUID.randomUUID(), 1,
                "v1", "v1 text", currentLetterValidation(), true, Instant.now(), Instant.now(),
                ExecutionPackageService.Sha256.of("v1 text"), null, "GENERATED", false);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of(foreign));

        var selection = selector.select(profile, job, application);

        assertThat(selection.coverLetter()).isNull();
    }

    @Test
    void theCoverLetterRequirementStaysUnknownWithoutCapturedFormEvidence() {
        when(formQuestions.coverLetterRequirement(job)).thenReturn(CoverLetterRequirement.UNKNOWN);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of());

        var selection = selector.select(profile, job, application);

        assertThat(selection.coverLetterRequirement()).isEqualTo(CoverLetterRequirement.UNKNOWN);
        assertThat(selection.warnings()).extracting(w -> w.get("code")).contains("COVER_LETTER_REQUIREMENT_UNKNOWN");
        assertThat(selection.blockers()).noneMatch(b -> String.valueOf(b.get("code")).contains("REQUIRED"));
    }

    @Test
    void aRequiredCoverLetterWithNoneAttachedBlocksThePackage() {
        when(formQuestions.coverLetterRequirement(job)).thenReturn(CoverLetterRequirement.REQUIRED);
        when(letters.findByJobIdForProfile(job, profile)).thenReturn(List.of());

        var selection = selector.select(profile, job, application);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.blockers()).extracting(b -> b.get("code")).contains("COVER_LETTER_REQUIRED_MISSING");
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private void stubCv(UUID cvId, String digest, boolean intact, Map<String, Object> validation,
                        Optional<ResumeAtsRepository.Review> review) {
        when(cvs.findForApplication(profile, job, application)).thenReturn(List.of(cvAnalysis(cvId, digest, validation)));
        when(cvs.checkedArtifact(profile, cvId)).thenReturn(Optional.of(artifact(digest, intact)));
        when(cvs.review(profile, cvId)).thenReturn(review);
    }

    private static Map<String, Object> currentValidation(boolean passed) {
        return Map.of("passed", passed, "validator_version", DocumentFactValidator.VERSION);
    }

    private static Map<String, Object> currentLetterValidation() {
        return Map.of("passed", true, "validator_version", DocumentFactValidator.VERSION);
    }

    private static ResumeAtsRepository.ArtifactCheck artifact(String digest, boolean intact) {
        byte[] bytes = new byte[]{1, 2, 3};
        return new ResumeAtsRepository.ArtifactCheck(bytes, intact ? digest : "other", digest,
                intact ? digest : "corrupted");
    }

    private static Optional<ResumeAtsRepository.Review> review(boolean approved, String digest) {
        return Optional.of(new ResumeAtsRepository.Review(approved, "owner", Instant.now(), digest));
    }

    private ResumeAtsAnalysis cvAnalysis(UUID cvId, String digest) {
        return cvAnalysis(cvId, digest, currentValidation(true));
    }

    private ResumeAtsAnalysis cvAnalysis(UUID cvId, String digest, Map<String, Object> validation) {
        Map<String, Object> atsReport = validation == null ? Map.of() : Map.of("validation", validation);
        return new ResumeAtsAnalysis(UUID.randomUUID(), profile, job, application, "hash", "Engineer", "Tech",
                List.of(), List.of(), Map.of(), List.of(), List.of(),
                atsReport, cvId, "cv text", 1, "snapshot", digest);
    }

    private CoverLetterRecord letter(int version, String body, boolean approved) {
        return new CoverLetterRecord(UUID.randomUUID(), profile, job, application, version, "v" + version,
                body, currentLetterValidation(), approved, Instant.now(), Instant.now(),
                ExecutionPackageService.Sha256.of(body), null, "GENERATED", false);
    }
}
