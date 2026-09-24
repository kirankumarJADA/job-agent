package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registration gate is the control that keeps a public production URL from
 * accepting arbitrary new accounts while the application's job/application/
 * notification data is still globally scoped. Its most important property is
 * the one that is easiest to get wrong: an unconfigured production deploy must
 * refuse registration, not allow it.
 */
class RegistrationGateTest {

    private static final String CODE = "correct-horse-battery";

    @Test
    void localDevelopmentWithoutARequiredCodeAllowsRegistration() {
        RegistrationGate gate = new RegistrationGate(false, "");

        assertThat(gate.evaluate(null)).isEqualTo(RegistrationGate.Decision.ALLOWED);
        assertThat(gate.evaluate("anything")).isEqualTo(RegistrationGate.Decision.ALLOWED);
        assertThat(gate.isInviteCodeRequired()).isFalse();
        assertThat(gate.registrationPossible()).isTrue();
    }

    @Test
    void requiredCodeThatMatchesAllowsRegistration() {
        RegistrationGate gate = new RegistrationGate(true, CODE);

        assertThat(gate.evaluate(CODE)).isEqualTo(RegistrationGate.Decision.ALLOWED);
        assertThat(gate.registrationPossible()).isTrue();
    }

    @Test
    void requiredCodeThatIsMissingRefusesRegistration() {
        RegistrationGate gate = new RegistrationGate(true, CODE);

        assertThat(gate.evaluate(null)).isEqualTo(RegistrationGate.Decision.MISSING_CODE);
        assertThat(gate.evaluate("")).isEqualTo(RegistrationGate.Decision.MISSING_CODE);
        assertThat(gate.evaluate("   ")).isEqualTo(RegistrationGate.Decision.MISSING_CODE);
    }

    @Test
    void requiredCodeThatIsWrongRefusesRegistration() {
        RegistrationGate gate = new RegistrationGate(true, CODE);

        assertThat(gate.evaluate("wrong")).isEqualTo(RegistrationGate.Decision.INVALID_CODE);
        assertThat(gate.evaluate(CODE + " ")).isEqualTo(RegistrationGate.Decision.ALLOWED);
    }

    @Test
    void requiredButUnconfiguredCodeRefusesRegistrationInsteadOfFallingOpen() {
        // The production failure mode this exists to prevent: the deploy turned
        // the requirement on but never set the secret. Open registration on a
        // globally-scoped dataset would be the wrong answer.
        RegistrationGate gate = new RegistrationGate(true, "");

        assertThat(gate.evaluate(null)).isEqualTo(RegistrationGate.Decision.NOT_CONFIGURED);
        assertThat(gate.evaluate("")).isEqualTo(RegistrationGate.Decision.NOT_CONFIGURED);
        assertThat(gate.evaluate("anything")).isEqualTo(RegistrationGate.Decision.NOT_CONFIGURED);
        assertThat(gate.registrationPossible()).isFalse();
    }

    @Test
    void aBlankConfiguredCodeIsNeverTreatedAsAMatchForABlankSuppliedCode() {
        // Guards a subtle bypass: if "no code configured" fell through to the
        // comparison, "" would equal "" and registration would be open.
        RegistrationGate blankServerCode = new RegistrationGate(true, "   ");
        assertThat(blankServerCode.evaluate("")).isEqualTo(RegistrationGate.Decision.NOT_CONFIGURED);
        assertThat(blankServerCode.evaluate("   ")).isEqualTo(RegistrationGate.Decision.NOT_CONFIGURED);
    }

    @Test
    void comparisonIgnoresSurroundingWhitespaceButNotCase() {
        RegistrationGate gate = new RegistrationGate(true, " " + CODE + " ");

        assertThat(gate.evaluate(CODE)).isEqualTo(RegistrationGate.Decision.ALLOWED);
        assertThat(gate.evaluate("  " + CODE + "  ")).isEqualTo(RegistrationGate.Decision.ALLOWED);
        assertThat(gate.evaluate(CODE.toUpperCase())).isEqualTo(RegistrationGate.Decision.INVALID_CODE);
    }
}
