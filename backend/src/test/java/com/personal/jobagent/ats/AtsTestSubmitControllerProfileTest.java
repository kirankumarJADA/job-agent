package com.personal.jobagent.ats;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security regression (Robin application-execution hardening): the adapter
 * payload-validation smoke test endpoint must not exist at all outside local
 * development — not behind a runtime flag that could be flipped, but absent
 * from the Spring context entirely, so a production (or default, CI) build
 * has no route for it to be reachable on.
 */
class AtsTestSubmitControllerProfileTest {

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withBean(AtsAdapterRegistry.class, () -> new AtsAdapterRegistry(List.of(new GreenhouseAdapter())))
                .withUserConfiguration(AtsTestSubmitController.class);
    }

    @Test
    void theTestSubmitControllerIsAbsentWithNoProfileActive() {
        context().run(ctx -> assertThat(ctx).doesNotHaveBean(AtsTestSubmitController.class));
    }

    @Test
    void theTestSubmitControllerIsAbsentUnderTheProdProfile() {
        context()
                .withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AtsTestSubmitController.class));
    }

    @Test
    void theTestSubmitControllerExistsOnlyUnderTheLocalProfile() {
        context()
                .withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(AtsTestSubmitController.class));
    }
}
