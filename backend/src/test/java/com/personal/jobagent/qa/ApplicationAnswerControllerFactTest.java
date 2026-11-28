package com.personal.jobagent.qa;

import com.personal.jobagent.apply.ApplyPackageGuard;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import com.personal.jobagent.security.UserRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationAnswerControllerFactTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesToConfirmEditedAnswerContainingUnsupportedFactualClaim() {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ProfileRepository profiles = mock(ProfileRepository.class);
        ProfileRecord profile = new ProfileRecord(profileId, userId, null, null, null, Map.of(), Map.of(), null, Map.of(), 1L, "READY");
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));

        ApplicationAnswerRepository answers = mock(ApplicationAnswerRepository.class);
        when(answers.findByIdForProfile(answerId, profileId)).thenReturn(Optional.of(new ApplicationAnswerRecord(
                answerId, profileId, jobId, UUID.randomUUID(),
                "Why are you qualified?", "UNIVERSAL_DISCOVERED", "",
                BigDecimal.ZERO, "NEEDS_USER_INPUT", Map.of(), false, Instant.now(), Instant.now())));

        UserRecord user = new UserRecord(userId, "a@example.com", null, "Candidate A", "uid-a", "LOCAL");
        AppUserDetails principal = new AppUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        var controller = new ApplicationAnswerController(
                mock(ApplicationAnswerService.class),
                answers,
                profiles,
                mock(AuditLogWriter.class),
                mock(ApplyPackageGuard.class),
                mock(JobRepository.class));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/v1/application-answers/" + answerId);

        var response = controller.updateAnswer(answerId,
                new ApplicationAnswerController.UpdateAnswerRequest("ANSWERED", "I earned a PhD in Physics in 1995.", true),
                req);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("unverified factual claims");
        verify(answers, never()).updateStatusForProfile(any(), anyString(), any(), anyBoolean(), any());
    }
}
