package com.personal.jobagent.mailbox.managed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationContentExtractorTest {

    private static final String EMPLOYER = "employer.example";

    @Test
    void findsTheCodeInTheUsualPhrasings() {
        assertThat(code("Verify your email", "Your verification code is 482913. It expires in 10 minutes.")).isEqualTo("482913");
        assertThat(code("482913 is your Employer verification code", "")).isEqualTo("482913");
        assertThat(code("Confirm your account", "Use the following code to verify your email:\n\n  482913\n\nThanks"))
                .isEqualTo("482913");
        assertThat(code("Sign in", "Your one-time passcode: 7310")).isEqualTo("7310");
        assertThat(code("Welcome", "Enter this code to finish signing up: 55012384")).isEqualTo("55012384");
        // The same code in subject and body is still one code.
        assertThat(code("Your code is 482913", "Hi, your verification code is 482913.")).isEqualTo("482913");
    }

    @Test
    void ordinaryNumbersAroundACodeDoNotMakeItAmbiguous() {
        String body = """
                Your verification code is 482913. This code expires in 10 minutes.
                Didn't request this code? Call 0800 123 4567 or +44 20 7946 0958.
                Need help verifying? Visit our help centre. © 2026 Employer Ltd, 1600 Example Road, Leeds LS1 4AB.
                Sent 2026-10-11 10:30.
                """;
        var extraction = VerificationContentExtractor.extract("Verify your email", body, List.of(), EMPLOYER);
        assertThat(extraction.codes()).containsExactly("482913");
        assertThat(extraction.ambiguous()).isFalse();
    }

    @Test
    void twoDifferentCodesAreAmbiguous() {
        var extraction = VerificationContentExtractor.extract("Your codes",
                "Your verification code is 482913. If that fails, use code 551200.", List.of(), EMPLOYER);
        assertThat(extraction.ambiguous()).isTrue();
        assertThat(extraction.code()).isNull();
    }

    @Test
    void numbersWithoutCodeLanguageAreNotCodes() {
        var extraction = VerificationContentExtractor.extract("Application update",
                "Thanks for applying to job 482913 in Leeds. Our office is at 1600 Example Road.", List.of(), EMPLOYER);
        assertThat(extraction.empty()).isTrue();
        // Four-digit years are never codes.
        assertThat(VerificationContentExtractor.extract("Your code", "Your verification code is 2026", List.of(), EMPLOYER)
                .codes()).isEmpty();
    }

    @Test
    void aVerificationLinkMustBeHttpsOnTheEmployerDomainAndLookLikeVerification() {
        List<String> links = List.of(
                "https://careers.employer.example/account/verify?token=abc123#top",
                "https://employer.example/privacy",
                "https://cdn.employer.example/logo.png",
                "http://employer.example/verify?token=plain-http",
                "https://user:pass@employer.example/verify?token=creds",
                "https://employer.example:8443/verify?token=port",
                "https://employer.example.attacker.example/verify?token=lookalike",
                "https://attacker.example/verify?token=elsewhere");
        var extraction = VerificationContentExtractor.extract("Confirm your email", "Click the button below.", links, EMPLOYER);
        assertThat(extraction.links()).containsExactly("https://careers.employer.example/account/verify?token=abc123");
        assertThat(extraction.link()).isEqualTo("https://careers.employer.example/account/verify?token=abc123");
        assertThat(extraction.ambiguous()).isFalse();
    }

    @Test
    void twoVerificationLinksWithoutACodeAreAmbiguous() {
        var extraction = VerificationContentExtractor.extract("Confirm", "", List.of(
                "https://employer.example/verify?token=one", "https://employer.example/confirm?token=two"), EMPLOYER);
        assertThat(extraction.ambiguous()).isTrue();
        assertThat(extraction.link()).isNull();
    }

    @Test
    void aCodeTakesPrecedenceOverLinks() {
        var extraction = VerificationContentExtractor.extract("Verify", "Your verification code is 482913",
                List.of("https://employer.example/verify?token=one", "https://employer.example/confirm?token=two"), EMPLOYER);
        assertThat(extraction.ambiguous()).isFalse();
        assertThat(extraction.code()).isEqualTo("482913");
        assertThat(extraction.link()).isNull();
    }

    private static String code(String subject, String text) {
        var extraction = VerificationContentExtractor.extract(subject, text, List.of(), EMPLOYER);
        assertThat(extraction.ambiguous()).as("ambiguous: %s", extraction.codes()).isFalse();
        return extraction.code();
    }
}
