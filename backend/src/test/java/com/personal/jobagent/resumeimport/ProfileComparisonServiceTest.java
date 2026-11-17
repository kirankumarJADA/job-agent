package com.personal.jobagent.resumeimport;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.*;
import com.personal.jobagent.resumeimport.dto.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileComparisonServiceTest {

    private final ProfileComparisonService service = new ProfileComparisonService();

    private WorkExperienceRecord experience(String company, String title, LocalDate start, LocalDate end) {
        return new WorkExperienceRecord(UuidV7.generate(), UuidV7.generate(), company, title, start, end, null, List.of(), 0);
    }

    private ExtractedExperience extractedExperience(String company, String title, LocalDate start, LocalDate end) {
        return new ExtractedExperience(company, title, null, start, end, false, List.of(), List.of(), List.of(), true, false);
    }

    @Test
    void identicalExperienceIsExisting() {
        LocalDate start = LocalDate.of(2021, 1, 1);
        LocalDate end = LocalDate.of(2023, 12, 1);
        var existing = List.of(experience("Acme Corp", "Software Engineer", start, end));
        var result = service.compareExperience(extractedExperience("acme corp", "software engineer", start, end), existing);
        assertThat(result.status()).isEqualTo(MatchStatus.EXISTING);
        assertThat(result.matchedExistingId()).isEqualTo(existing.get(0).id());
    }

    @Test
    void sameCompanyAndTitleButDifferentDatesIsConflict() {
        var existing = List.of(experience("Acme Corp", "Software Engineer",
                LocalDate.of(2021, 1, 1), LocalDate.of(2022, 1, 1)));
        var result = service.compareExperience(extractedExperience("Acme Corp", "Software Engineer",
                LocalDate.of(2021, 1, 1), LocalDate.of(2023, 12, 1)), existing);
        assertThat(result.status()).isEqualTo(MatchStatus.CONFLICT);
    }

    @Test
    void sameCompanyDifferentTitleIsPossibleDuplicate() {
        var existing = List.of(experience("Acme Corp", "Software Engineer",
                LocalDate.of(2021, 1, 1), LocalDate.of(2022, 1, 1)));
        var result = service.compareExperience(extractedExperience("Acme Corp", "Staff Engineer",
                LocalDate.of(2023, 1, 1), null), existing);
        assertThat(result.status()).isEqualTo(MatchStatus.POSSIBLE_DUPLICATE);
    }

    @Test
    void unrelatedEmployerIsNew() {
        var existing = List.of(experience("Acme Corp", "Software Engineer",
                LocalDate.of(2021, 1, 1), LocalDate.of(2022, 1, 1)));
        var result = service.compareExperience(extractedExperience("Globex", "Engineer", LocalDate.of(2020, 1, 1), null), existing);
        assertThat(result.status()).isEqualTo(MatchStatus.NEW);
    }

    @Test
    void missingCompanyOrTitleNeedsReview() {
        var result = service.compareExperience(extractedExperience(null, "Engineer", null, null), List.of());
        assertThat(result.status()).isEqualTo(MatchStatus.NEEDS_REVIEW);
    }

    @Test
    void educationDuplicateDetection() {
        var existing = List.of(new EducationRecord(UuidV7.generate(), UuidV7.generate(),
                "State University", "BSc Computer Science", "Computer Science", 2017, 2021, null));
        var extracted = new ExtractedEducation("State University", "BSc Computer Science", "Computer Science", 2017, 2021, null, true, false);
        assertThat(service.compareEducation(extracted, existing).status()).isEqualTo(MatchStatus.EXISTING);

        var conflicting = new ExtractedEducation("State University", "BSc Computer Science", "Computer Science", 2017, 2020, null, true, false);
        assertThat(service.compareEducation(conflicting, existing).status()).isEqualTo(MatchStatus.CONFLICT);
    }

    @Test
    void skillDuplicateDetectionIsCaseInsensitive() {
        var existing = List.of(new SkillRecord(UuidV7.generate(), UuidV7.generate(), "Java", "Backend", null, null, null));
        assertThat(service.compareSkill(new ExtractedSkill("java", "Technical", true), existing).status())
                .isEqualTo(MatchStatus.EXISTING);
        assertThat(service.compareSkill(new ExtractedSkill("Kotlin", "Technical", true), existing).status())
                .isEqualTo(MatchStatus.NEW);
    }

    @Test
    void skillMatchingKeepsPunctuationSoCSharpIsNotCPlusPlus() {
        var existing = List.of(new SkillRecord(UuidV7.generate(), UuidV7.generate(), "C++", "Language", null, null, null));
        assertThat(service.compareSkill(new ExtractedSkill("C#", "Programming Language", true), existing).status())
                .isEqualTo(MatchStatus.NEW);
        assertThat(service.compareSkill(new ExtractedSkill("C", "Programming Language", true), existing).status())
                .isEqualTo(MatchStatus.NEW);
        assertThat(service.compareSkill(new ExtractedSkill(" c++ ", "Programming Language", true), existing).status())
                .isEqualTo(MatchStatus.EXISTING);
    }

    @Test
    void certificationSameIssuerDifferentCredentialIsConflict() {
        var existing = List.of(new CertificationRecord(UuidV7.generate(), UuidV7.generate(),
                "AWS Certified Solutions Architect", "Amazon Web Services", LocalDate.of(2020, 1, 1), "OLD-ID"));
        var extracted = new ExtractedCertification("AWS Certified Solutions Architect", "Amazon Web Services",
                LocalDate.of(2022, 1, 1), null, "NEW-ID", null, true, false);
        assertThat(service.compareCertification(extracted, existing).status()).isEqualTo(MatchStatus.CONFLICT);
    }

    @Test
    void projectWithDifferentUrlIsConflict() {
        var existing = List.of(new ProjectRecord(UuidV7.generate(), UuidV7.generate(), "Robin", "An AI job agent", "https://old.example", List.of(), 0));
        var extracted = new ExtractedProject("Robin", "An AI job agent", "https://new.example", List.of(), List.of(), true, false);
        assertThat(service.compareProject(extracted, existing).status()).isEqualTo(MatchStatus.CONFLICT);
    }
}
