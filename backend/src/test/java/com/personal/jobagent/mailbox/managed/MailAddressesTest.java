package com.personal.jobagent.mailbox.managed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailAddressesTest {

    @Test
    void bareAddressesAreValidatedAndLowerCased() {
        assertThat(MailAddresses.bareAddress(" U7KQ2M9X4VB3TN5P@Mail.Robin.Example ")).contains("u7kq2m9x4vb3tn5p@mail.robin.example");
        assertThat(MailAddresses.bareAddress("a@b.example, c@d.example")).isEmpty();
        assertThat(MailAddresses.bareAddress("Name <a@b.example>")).isEmpty();
        assertThat(MailAddresses.bareAddress("a@localhost")).isEmpty();
        assertThat(MailAddresses.bareAddress("a..b@b.example")).isEmpty();
        assertThat(MailAddresses.bareAddress("@b.example")).isEmpty();
        assertThat(MailAddresses.bareAddress(null)).isEmpty();
    }

    @Test
    void theFromMailboxIsTheBracketedAddressOnly() {
        assertThat(MailAddresses.singleMailbox("Employer Talent <No-Reply@Careers.Employer.Example>"))
                .contains("no-reply@careers.employer.example");
        assertThat(MailAddresses.singleMailbox("no-reply@employer.example")).contains("no-reply@employer.example");
        // A quoted display name never counts, whatever address it shows.
        assertThat(MailAddresses.singleMailbox("\"hr@employer.example\" <x@attacker.example>"))
                .contains("x@attacker.example");
        assertThat(MailAddresses.singleMailbox("\"Smith, Jane <jane@employer.example>\" <jane@other.example>"))
                .contains("jane@other.example");
    }

    @Test
    void ambiguousFromHeadersYieldNoAddress() {
        assertThat(MailAddresses.singleMailbox("a@employer.example, b@employer.example")).isEmpty();
        assertThat(MailAddresses.singleMailbox("A <a@employer.example>, B <b@attacker.example>")).isEmpty();
        assertThat(MailAddresses.singleMailbox("hr@employer.example <x@attacker.example>")).isEmpty();
        assertThat(MailAddresses.singleMailbox("Name <a@employer.example> trailing")).isEmpty();
        assertThat(MailAddresses.singleMailbox("Name <a@employer.example")).isEmpty();
        assertThat(MailAddresses.singleMailbox("\"unbalanced <a@employer.example>")).isEmpty();
        assertThat(MailAddresses.singleMailbox("undisclosed-recipients:;")).isEmpty();
        assertThat(MailAddresses.singleMailbox("")).isEmpty();
    }

    @Test
    void employerDomainsAreNormalisedAndSubdomainsMatchOnLabelBoundaries() {
        assertThat(MailAddresses.employerDomain(" WWW.Employer.Example. ")).contains("employer.example");
        assertThat(MailAddresses.employerDomain("https://employer.example")).isEmpty();
        assertThat(MailAddresses.employerDomain("employer")).isEmpty();
        assertThat(MailAddresses.sameOrSubdomain("employer.example", "employer.example")).isTrue();
        assertThat(MailAddresses.sameOrSubdomain("mail.employer.example", "employer.example")).isTrue();
        assertThat(MailAddresses.sameOrSubdomain("evilemployer.example", "employer.example")).isFalse();
        assertThat(MailAddresses.sameOrSubdomain("employer.example.attacker.example", "employer.example")).isFalse();
        assertThat(MailAddresses.sameOrSubdomain("example", "employer.example")).isFalse();
    }
}
