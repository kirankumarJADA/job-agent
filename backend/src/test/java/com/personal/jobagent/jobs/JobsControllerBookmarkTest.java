package com.personal.jobagent.jobs;

import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the candidate job bookmarks API:
 * POST /api/v1/jobs/{id}/bookmark and DELETE /api/v1/jobs/{id}/bookmark.
 * Verifies profile scoping, idempotency, unknown job handling, and candidate isolation.
 */
class JobsControllerBookmarkTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PROFILE_A = UUID.randomUUID();
    private static final UUID PROFILE_B = UUID.randomUUID();

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final OwnerContext ownerContext = mock(OwnerContext.class);
    private JobsController controller;

    @BeforeEach
    void setUp() {
        controller = new JobsController(jobRepository, null, ownerContext, null);
        when(ownerContext.profileIdOrNull()).thenReturn(PROFILE_A);
        when(jobRepository.existsById(JOB)).thenReturn(true);
    }

    @Test
    void candidateCanBookmarkAValidJob() {
        when(jobRepository.addBookmark(PROFILE_A, JOB)).thenReturn(true);

        ResponseEntity<?> response = controller.bookmarkJob(JOB);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of(
                "bookmarked", true,
                "job_id", JOB.toString()
        ));
        verify(jobRepository).addBookmark(PROFILE_A, JOB);
    }

    @Test
    void repeatingPostIsIdempotentAndDoesNotDuplicate() {
        // First bookmark succeeds (returns true)
        when(jobRepository.addBookmark(PROFILE_A, JOB)).thenReturn(true);
        ResponseEntity<?> resp1 = controller.bookmarkJob(JOB);
        assertThat(resp1.getStatusCode().value()).isEqualTo(200);

        // Second bookmark returns false (already bookmarked due to ON CONFLICT DO NOTHING)
        when(jobRepository.addBookmark(PROFILE_A, JOB)).thenReturn(false);
        ResponseEntity<?> resp2 = controller.bookmarkJob(JOB);
        assertThat(resp2.getStatusCode().value()).isEqualTo(200);
        assertThat(resp2.getBody()).isEqualTo(Map.of(
                "bookmarked", true,
                "job_id", JOB.toString()
        ));
    }

    @Test
    void candidateCanRemoveTheirOwnBookmark() {
        when(jobRepository.removeBookmark(PROFILE_A, JOB)).thenReturn(true);

        ResponseEntity<?> response = controller.removeBookmark(JOB);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of(
                "bookmarked", false,
                "job_id", JOB.toString()
        ));
        verify(jobRepository).removeBookmark(PROFILE_A, JOB);
    }

    @Test
    void repeatingDeleteIsSafeAndIdempotent() {
        // First delete removes the bookmark
        when(jobRepository.removeBookmark(PROFILE_A, JOB)).thenReturn(true);
        ResponseEntity<?> resp1 = controller.removeBookmark(JOB);
        assertThat(resp1.getStatusCode().value()).isEqualTo(200);

        // Second delete finds no bookmark to remove, returns 200 safely
        when(jobRepository.removeBookmark(PROFILE_A, JOB)).thenReturn(false);
        ResponseEntity<?> resp2 = controller.removeBookmark(JOB);
        assertThat(resp2.getStatusCode().value()).isEqualTo(200);
        assertThat(resp2.getBody()).isEqualTo(Map.of(
                "bookmarked", false,
                "job_id", JOB.toString()
        ));
    }

    @Test
    void unknownJobReturnsNotFoundForBookmarkAndUnbookmark() {
        UUID unknownJob = UUID.randomUUID();
        when(jobRepository.existsById(unknownJob)).thenReturn(false);

        assertThat(controller.bookmarkJob(unknownJob).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.removeBookmark(unknownJob).getStatusCode().value()).isEqualTo(404);

        verify(jobRepository, never()).addBookmark(any(), eq(unknownJob));
        verify(jobRepository, never()).removeBookmark(any(), eq(unknownJob));
    }

    @Test
    void missingProfileCannotCreateOrDeleteBookmarks() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);

        ResponseEntity<?> postResp = controller.bookmarkJob(JOB);
        assertThat(postResp.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<?> delResp = controller.removeBookmark(JOB);
        assertThat(delResp.getStatusCode().value()).isEqualTo(400);

        verify(jobRepository, never()).addBookmark(any(), any());
        verify(jobRepository, never()).removeBookmark(any(), any());
    }

    @Test
    void candidateACannotModifyCandidateBBookmark() {
        // Candidate B is authenticated
        when(ownerContext.profileIdOrNull()).thenReturn(PROFILE_B);
        when(jobRepository.removeBookmark(PROFILE_B, JOB)).thenReturn(false);

        // Candidate B attempts to delete bookmark: operation is strictly scoped to PROFILE_B
        ResponseEntity<?> response = controller.removeBookmark(JOB);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Verify repository was called ONLY with PROFILE_B, never PROFILE_A
        verify(jobRepository).removeBookmark(PROFILE_B, JOB);
        verify(jobRepository, never()).removeBookmark(eq(PROFILE_A), any());
    }

    @Test
    void toWireExposesBookmarkedStateMatchingCandidate() {
        JobRecord record = new JobRecord(
                JOB, UUID.randomUUID(), "ext-1", UUID.randomUUID(), "Acme Corp", "Senior Engineer",
                "London", "London", "UK", "HYBRID", "FULL_TIME", "SENIOR",
                null, null, "GBP", "Description", java.util.List.of("Java"),
                "https://example.com/apply", "https://example.com/job", java.time.Instant.now(), "DISCOVERED"
        );

        JobRepository.FeedItem bookmarkedItem = new JobRepository.FeedItem(
                record, java.time.Instant.now(), java.time.Instant.now(),
                false, false, "Greenhouse", "GREENHOUSE", 95, "APPLY", java.time.Instant.now(), null, true
        );

        JobRepository.FeedItem unbookmarkedItem = new JobRepository.FeedItem(
                record, java.time.Instant.now(), java.time.Instant.now(),
                false, false, "Greenhouse", "GREENHOUSE", 95, "APPLY", java.time.Instant.now(), null, false
        );

        Map<String, Object> wireBookmarked = JobsController.toWire(bookmarkedItem);
        assertThat(wireBookmarked.get("bookmarked")).isEqualTo(true);

        Map<String, Object> wireUnbookmarked = JobsController.toWire(unbookmarkedItem);
        assertThat(wireUnbookmarked.get("bookmarked")).isEqualTo(false);
    }
}
