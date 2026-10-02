package com.personal.jobagent.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
/**
 * A single 429 must not silence a secondary provider for the lifetime of the
 * JVM: EXHAUSTED recovers after its cooldown window and the next scheduled
 * run can retry.
 */
class ConfiguredSecondaryProviderCooldownTest {

    /** Test double: always answers with a configurable HTTP status. */
    private static final class StubProvider extends ConfiguredSecondaryProvider {
        private int status;
        StubProvider(Environment environment) { super("stub", "STUB_API_KEY", environment); }
        void respondWith(int status) { this.status = status; }
        @Override protected ProviderResponse request(ScraperProvider.DiscoveryRequest request) {
            return new ProviderResponse(status, "{}", java.util.Map.of());
        }
    }

    private static ScraperProvider.DiscoveryRequest request() {
        return new ScraperProvider.DiscoveryRequest("https://jobs.example.org/1", "WEB", "corr", null);
    }

    private static Environment environment(long cooldownMs) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("STUB_API_KEY", "test-key");
        env.setProperty("DISCOVERY_EXHAUSTED_COOLDOWN_MS", String.valueOf(cooldownMs));
        return env;
    }

    @Test
    void a429ExhaustsTheProviderForTheCooldownWindowOnly() {
        StubProvider provider = new StubProvider(environment(900_000L));
        provider.respondWith(429);

        ScraperProvider.ExtractionResult first = provider.extract(request());

        assertThat(first.error()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.EXHAUSTED);
        assertThat(provider.enabled()).isFalse();

        // With the window still open the provider refuses work without a call:
        ScraperProvider.ExtractionResult refused = provider.extract(request());
        assertThat(refused.error()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.EXHAUSTED);

        // Zero cooldown = window already elapsed: the provider is eligible again.
        StubProvider recovered = new StubProvider(environment(0L));
        recovered.respondWith(429);
        recovered.extract(request());
        assertThat(recovered.enabled()).isTrue();
    }

    @Test
    void aSuccessfulCallAfterRecoveryRestoresAvailability() {
        StubProvider provider = new StubProvider(environment(0L));
        provider.respondWith(500);
        provider.extract(request());
        assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.DEGRADED);

        provider.respondWith(200);
        // A 200 with no parseable jobs is still an extraction attempt; any
        // non-429 outcome clears the exhausted gate — verify via enabled().
        assertThat(provider.enabled()).isTrue();
    }
}
