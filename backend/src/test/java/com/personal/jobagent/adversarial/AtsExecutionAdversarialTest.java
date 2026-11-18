package com.personal.jobagent.adversarial;

import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement;
import com.personal.jobagent.automation.ApplyDocumentSelector;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.resume.ResumeAtsAnalysis;
import com.personal.jobagent.resume.ResumeAtsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

@DisplayName("Adversarial QA Suite: ATS Execution Resilience, Tamper Resistance & Document Integrity")
class AtsExecutionAdversarialTest {

    private final UUID profileId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

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

        when(formQuestions.coverLetterRequirement(jobId)).thenReturn(CoverLetterRequirement.OPTIONAL);
        when(letters.findByJobIdForProfile(jobId, profileId)).thenReturn(List.of());
    }

    private void stubCv(UUID cvId, String digest, boolean intact, Map<String, Object> validation,
                        Optional<ResumeAtsRepository.Review> review) {
        Map<String, Object> atsReport = validation == null ? Map.of() : Map.of("validation", validation);
        ResumeAtsAnalysis analysis = new ResumeAtsAnalysis(
                UUID.randomUUID(), profileId, jobId, applicationId, "hash", "Engineer", "Tech",
                List.of(), List.of(), Map.of(), List.of(), List.of(),
                atsReport, cvId, "cv text", 1, "snapshot", digest
        );

        byte[] bytes = new byte[]{1, 2, 3};
        ResumeAtsRepository.ArtifactCheck artifactCheck = new ResumeAtsRepository.ArtifactCheck(
                bytes, intact ? digest : "other", digest, intact ? digest : "corrupted"
        );

        when(cvs.findForApplication(profileId, jobId, applicationId)).thenReturn(List.of(analysis));
        when(cvs.checkedArtifact(profileId, cvId)).thenReturn(Optional.of(artifactCheck));
        when(cvs.review(profileId, cvId)).thenReturn(review);
    }

    @Test
    @DisplayName("Adversarial: Tampered PDF digest blocks application packaging immediately")
    void testDigestMismatchBlocksPackaging() {
        UUID cvId = UUID.randomUUID();
        // Candidate approved digest 'original-sha256', but stored artifact digest is 'tampered-sha256'
        stubCv(cvId, "tampered-sha256", true, Map.of("passed", true, "validator_version", DocumentFactValidator.VERSION),
                Optional.of(new ResumeAtsRepository.Review(true, "owner", Instant.now(), "original-sha256")));

        var selection = selector.select(profileId, jobId, applicationId);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.cv()).isNull();
        assertThat(selection.blockers()).anyMatch(b -> "CV_REVIEW_DIGEST_MISMATCH".equals(b.get("code")));
    }

    @Test
    @DisplayName("Adversarial: Corrupted disk artifact triggers integrity blocker")
    void testCorruptedArtifactBlocksPackaging() {
        UUID cvId = UUID.randomUUID();
        stubCv(cvId, "valid-sha256", false, Map.of("passed", true, "validator_version", DocumentFactValidator.VERSION),
                Optional.of(new ResumeAtsRepository.Review(true, "owner", Instant.now(), "valid-sha256")));

        var selection = selector.select(profileId, jobId, applicationId);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.cv()).isNull();
        assertThat(selection.blockers()).anyMatch(b -> "CV_ARTIFACT_INTEGRITY_FAILED".equals(b.get("code")));
    }

    @Test
    @DisplayName("Adversarial: Unvalidated or legacy CV without rule versioning is rejected")
    void testLegacyUnvalidatedCvBlocksPackaging() {
        UUID cvId = UUID.randomUUID();
        // Missing validation section
        stubCv(cvId, "valid-sha256", true, null,
                Optional.of(new ResumeAtsRepository.Review(true, "owner", Instant.now(), "valid-sha256")));

        var selection = selector.select(profileId, jobId, applicationId);

        assertThat(selection.blocked()).isTrue();
        assertThat(selection.cv()).isNull();
        assertThat(selection.blockers()).anyMatch(b -> "CV_LEGACY_UNVALIDATED".equals(b.get("code")));
    }

    @Test
    @DisplayName("Adversarial: Perfectly intact and verified CV produces unblocked selection with exact digest")
    void testIntactVerifiedCvPassesCleanly() {
        UUID cvId = UUID.randomUUID();
        String digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        stubCv(cvId, digest, true, Map.of("passed", true, "validator_version", DocumentFactValidator.VERSION),
                Optional.of(new ResumeAtsRepository.Review(true, "owner", Instant.now(), digest)));

        var selection = selector.select(profileId, jobId, applicationId);

        assertThat(selection.blocked()).isFalse();
        assertThat(selection.cv()).isNotNull();
        assertThat(selection.cv().versionId()).isEqualTo(cvId);
        assertThat(selection.cv().pdfSha256()).isEqualTo(digest);
    }
}
