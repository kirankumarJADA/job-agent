package com.personal.jobagent.identity;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class CredentialVaultTest {
    @Test void references_are_one_shot(){
        CredentialVault vault=new CredentialVault();
        String ref=vault.store("local-only-secret",Duration.ofMinutes(1));
        assertThat(vault.resolveOnce(ref)).isEqualTo("local-only-secret");
        assertThatThrownBy(()->vault.resolveOnce(ref)).isInstanceOf(IllegalStateException.class);
    }
    @Test void empty_values_are_rejected(){
        CredentialVault vault=new CredentialVault();
        assertThatThrownBy(()->vault.store("",Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
