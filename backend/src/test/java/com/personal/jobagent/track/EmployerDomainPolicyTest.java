package com.personal.jobagent.track;

import com.personal.jobagent.track.EmployerDomainPolicy.ApplicationTarget;
import com.personal.jobagent.track.EmployerDomainPolicy.BindingStatus;
import com.personal.jobagent.track.EmployerDomainPolicy.DomainBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmployerDomainPolicyTest {

    private final EmployerDomainPolicy policy = new EmployerDomainPolicy();

    @Test
    void corporateCareerSiteUrlBindsCorporateDomainAndSubdomains() {
        ApplicationTarget target = ApplicationTarget.ofUrls(
                "https://careers.acme.com/apply/123", null, "Acme Corp", List.of());

        DomainBinding direct = policy.evaluate(target, "acme.com", "acme.com");
        DomainBinding subdomain = policy.evaluate(target, "mail.acme.com", "acme.com");
        DomainBinding unrelated = policy.evaluate(target, "unrelated.example", "unrelated.example");

        assertThat(direct.isBound()).isTrue();
        assertThat(subdomain.isBound()).isTrue();
        assertThat(unrelated.status()).isEqualTo(BindingStatus.UNBOUND_MISMATCH);
        assertThat(unrelated.reasonCode()).isEqualTo("SENDER_DOMAIN_NOT_BOUND_TO_EMPLOYER");
    }

    @Test
    void multiLabelPublicSuffixPreventsSiblingDomainCollision() {
        ApplicationTarget ukTarget = ApplicationTarget.ofUrls(
                "https://jobs.bbc.co.uk/roles/42", null, "BBC", List.of());

        assertThat(policy.evaluate(ukTarget, "bbc.co.uk", "bbc.co.uk").isBound()).isTrue();
        assertThat(policy.evaluate(ukTarget, "careers.bbc.co.uk", "bbc.co.uk").isBound()).isTrue();
        assertThat(policy.evaluate(ukTarget, "evil.co.uk", "evil.co.uk").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
        assertThat(policy.evaluate(ukTarget, "co.uk", "co.uk").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
    }

    @Test
    void multiTenantHostingSuffixRequiresExactHostMatch() {
        ApplicationTarget hostedTarget = ApplicationTarget.ofUrls(
                "https://acme-careers.netlify.app/apply", null, "Acme", List.of());

        assertThat(policy.evaluate(hostedTarget, "acme-careers.netlify.app", "acme-careers.netlify.app").isBound())
                .isTrue();
        assertThat(policy.evaluate(hostedTarget, "attacker.netlify.app", "attacker.netlify.app").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
        assertThat(policy.evaluate(hostedTarget, "netlify.app", "netlify.app").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
    }

    @Test
    void vettedAtsPlatformsBindKnownNotificationDomainsWhileBlockingUnrelatedSenders() {
        ApplicationTarget greenhouse = ApplicationTarget.ofUrls(
                "https://boards.greenhouse.io/acme/jobs/123", null, "Acme", List.of());
        ApplicationTarget ashby = ApplicationTarget.ofUrls(
                "https://jobs.ashbyhq.com/linear/role-1", null, "Linear", List.of());
        ApplicationTarget workday = ApplicationTarget.ofUrls(
                "https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIA/job/1", null, "NVIDIA", List.of());

        assertThat(policy.evaluate(greenhouse, "us.greenhouse-mail.io", "greenhouse-mail.io").isBound()).isTrue();
        assertThat(policy.evaluate(greenhouse, "greenhouse.io", "greenhouse.io").isBound()).isTrue();
        assertThat(policy.evaluate(ashby, "ashbyhq.com", "ashbyhq.com").isBound()).isTrue();
        assertThat(policy.evaluate(workday, "myworkday.com", "myworkday.com").isBound()).isTrue();

        assertThat(policy.evaluate(greenhouse, "unrelated.example", "unrelated.example").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
        assertThat(policy.evaluate(ashby, "greenhouse.io", "greenhouse.io").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
    }

    @Test
    void serverConfiguredVettedMappingsAuthorizeExplicitCustomSenderDomainsOnly() {
        EmployerDomainPolicy configured = new EmployerDomainPolicy(
                "greenhouse:acme=acme.com,mail.acme.com;nvidia.com=talentesp.example");

        ApplicationTarget greenhouseAcme = ApplicationTarget.ofUrls(
                "https://boards.greenhouse.io/acme/jobs/555", null, "Acme", List.of());
        ApplicationTarget greenhouseOther = ApplicationTarget.ofUrls(
                "https://boards.greenhouse.io/otherco/jobs/999", null, "OtherCo", List.of());

        assertThat(configured.evaluate(greenhouseAcme, "mail.acme.com", "acme.com").isBound()).isTrue();
        assertThat(configured.evaluate(greenhouseOther, "mail.acme.com", "acme.com").status())
                .isEqualTo(BindingStatus.UNBOUND_MISMATCH);
    }

    @Test
    void jobBoardsAndMissingUrlsFailClosedAsUnknownTargetDomain() {
        ApplicationTarget boardOnly = ApplicationTarget.ofUrls(
                "https://www.indeed.com/viewjob?jk=123", null, "Acme", List.of());
        ApplicationTarget missing = ApplicationTarget.ofUrls(null, null, "Acme", List.of());

        assertThat(policy.evaluate(boardOnly, "indeed.com", "indeed.com").status())
                .isEqualTo(BindingStatus.UNBOUND_UNKNOWN);
        assertThat(policy.evaluate(missing, "acme.com", "acme.com").status())
                .isEqualTo(BindingStatus.UNBOUND_UNKNOWN);
        assertThat(policy.evaluate(null, "acme.com", "acme.com").status())
                .isEqualTo(BindingStatus.UNBOUND_UNKNOWN);
    }
}
