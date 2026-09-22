package com.personal.jobagent.system;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTlsBindingTest {

    @Test
    void canonicalSpringBootRedisSslPropertyEnablesLettuceTls() {
        redisContext()
                .withPropertyValues(
                        "spring.data.redis.host=upstash.example",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.password=token-not-logged",
                        "spring.data.redis.ssl.enabled=true")
                .run(this::assertTlsAndCredentials);
    }

    @Test
    void defaultConfigurationMapsRenderSslVariableToTheEffectiveLettuceFactory() {
        redisConfigurationContext()
                .withPropertyValues(
                        "SPRING_REDIS_HOST=upstash.example",
                        "SPRING_REDIS_PORT=6379",
                        "SPRING_REDIS_PASSWORD=token-not-logged",
                        "SPRING_REDIS_SSL=true")
                .run(this::assertTlsAndCredentials);
    }

    @Test
    void productionConfigurationMapsRenderSslVariableToTheEffectiveLettuceFactory() {
        redisConfigurationContext()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "SPRING_REDIS_HOST=upstash.example",
                        "SPRING_REDIS_PORT=6379",
                        "SPRING_REDIS_PASSWORD=token-not-logged",
                        "SPRING_REDIS_SSL=true")
                .run(this::assertTlsAndCredentials);
    }

    @Test
    void autoConfiguredFactoryUsesJvmDnsResolver() {
        redisContext()
                .withUserConfiguration(com.personal.jobagent.config.RedisLettuceConfiguration.class)
                .withPropertyValues("spring.data.redis.host=upstash.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getClientConfiguration().getClientResources())
                            .get()
                            .extracting(resources -> resources.dnsResolver())
                            .isSameAs(DnsResolvers.JVM_DEFAULT);
                });
    }

    @Test
    void redisCustomizerUsesJvmDnsResolverForFactoryConnections() {
        DefaultClientResources.Builder builder = DefaultClientResources.builder();
        new com.personal.jobagent.config.RedisLettuceConfiguration()
                .redisJvmDnsResolver()
                .customize(builder);
        DefaultClientResources resources = builder.build();
        try {
            assertThat(resources.dnsResolver()).isSameAs(DnsResolvers.JVM_DEFAULT);
        } finally {
            resources.shutdown();
        }
    }

    @Test
    void sharedSpringFactoryAcquiresAndPingsAgainstLocalRedis() {
        Assumptions.assumeTrue(localRedisAvailable(), "local Docker Redis is not reachable");
        redisContext()
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.timeout=2s",
                        "spring.data.redis.connect-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getShareNativeConnection()).isTrue();
                    try (var connection = factory.getConnection()) {
                        assertThat(connection.ping()).isEqualTo("PONG");
                    } finally {
                        factory.destroy();
                    }
                });
    }

    @Test
    void springFactoryUsesAutomaticProtocolUnlessExplicitlyConfigured() {
        redisContext()
                .withPropertyValues(
                        "spring.data.redis.host=upstash.example",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.password=token-not-logged")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getClientConfiguration().getClientOptions()).get()
                            .extracting(io.lettuce.core.ClientOptions::getConfiguredProtocolVersion)
                            .isNull();
                });
    }

    private static boolean localRedisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 1000);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Test
    void lettuceClientTimeoutsAreBoundedBelowDiagnosticBudget() {
        // The post-startup diagnostic budgets 3s for the pooled phase; the
        // Lettuce client itself must fail faster than that so the diagnostic
        // reports the client's own error instead of its own budget firing.
        redisConfigurationContext()
                .withPropertyValues(
                        "SPRING_REDIS_HOST=upstash.example",
                        "SPRING_REDIS_PORT=6379",
                        "SPRING_REDIS_PASSWORD=token-not-logged",
                        "SPRING_REDIS_SSL=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getTimeout())
                            .isLessThan(java.time.Duration.ofSeconds(3).toMillis());
                    assertThat(factory.getTimeout()).isGreaterThan(0);
                });
    }

    private ApplicationContextRunner redisConfigurationContext() {
        return redisContext().withInitializer(new ConfigDataApplicationContextInitializer());
    }

    private ApplicationContextRunner redisContext() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.repositories.enabled=false");
    }

    private void assertTlsAndCredentials(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

        assertThat(factory.getHostName()).isEqualTo("upstash.example");
        assertThat(factory.getPort()).isEqualTo(6379);
        assertThat(factory.isUseSsl()).isTrue();
        assertThat(factory.getPassword()).isEqualTo("token-not-logged");
    }
}
