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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.personal.jobagent.config.RedisLettuceConfiguration.InitializationTimeoutLettuceConnectionFactory;

import java.time.Duration;

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
                        "spring.data.redis.connect-timeout=2s",
                        "app.redis.connection-initialization-timeout=10s")
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
    void customFactorySeparatesInitializationTimeoutFromCommandTimeout() {
        redisContext()
                .withUserConfiguration(com.personal.jobagent.config.RedisLettuceConfiguration.class)
                .withPropertyValues(
                        "spring.data.redis.host=upstash.example",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.password=token-not-logged",
                        "spring.data.redis.ssl.enabled=true",
                        "spring.data.redis.timeout=2s",
                        "spring.data.redis.connect-timeout=2s",
                        "app.redis.connection-initialization-timeout=10s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory).isInstanceOf(InitializationTimeoutLettuceConnectionFactory.class);
                    InitializationTimeoutLettuceConnectionFactory customFactory =
                            (InitializationTimeoutLettuceConnectionFactory) factory;
                    assertThat(customFactory.getInitializationTimeout()).isEqualTo(Duration.ofSeconds(10));
                    // The Lettuce handshake budget is read from the client's RedisURI
                    // (ConnectionBuilder.apply -> RedisHandshakeHandler.initializeTimeout);
                    // the custom factory must raise THAT value.
                    io.lettuce.core.RedisURI uri = (io.lettuce.core.RedisURI)
                            new org.springframework.beans.DirectFieldAccessor(factory.getNativeClient())
                                    .getPropertyValue("redisURI");
                    // Lettuce copies RedisURI.getTimeout() into ConnectionBuilder.timeout
                    // (ConnectionBuilder.apply) and that value becomes
                    // RedisHandshakeHandler.initializeTimeout, i.e. the bound reported as
                    // "Connection initialization timed out after N second(s)". So this URI
                    // timeout IS the connection initialization timeout.
                    assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(10));
                    // The client default timeout stays at the command budget: it feeds
                    // the per-command expiry writer, so it must NOT be loosened.
                    assertThat(factory.getNativeClient().getDefaultTimeout())
                            .isEqualTo(Duration.ofSeconds(2));
                    assertThat(factory.getClientConfiguration().getClientOptions()).get()
                            .extracting(options -> options.getTimeoutOptions().isApplyConnectionTimeout())
                            .isEqualTo(true);
                    assertThat(factory.getTimeout()).isEqualTo(Duration.ofSeconds(2).toMillis());
                    assertThat(factory.getClientConfiguration().getCommandTimeout())
                            .isEqualTo(Duration.ofSeconds(2));
                    assertThat(factory.getClientConfiguration().getClientOptions()).get()
                            .extracting(io.lettuce.core.ClientOptions::getConfiguredProtocolVersion)
                            .isNull();
                    assertThat(factory.isUseSsl()).isTrue();
                    assertThat(factory.getPassword()).isEqualTo("token-not-logged");
                    assertThat(factory.getShareNativeConnection()).isTrue();
                });
    }

    @Test
    void initializationTimeoutPropertyDrivesHandshakeBudgetNotCommandTimeout() {
        redisContext()
                .withUserConfiguration(com.personal.jobagent.config.RedisLettuceConfiguration.class)
                .withPropertyValues(
                        "spring.data.redis.host=upstash.example",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.password=token-not-logged",
                        "spring.data.redis.timeout=2s",
                        "spring.data.redis.connect-timeout=2s",
                        "app.redis.connection-initialization-timeout=25s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    io.lettuce.core.RedisURI uri = (io.lettuce.core.RedisURI)
                            new org.springframework.beans.DirectFieldAccessor(factory.getNativeClient())
                                    .getPropertyValue("redisURI");
                    // Handshake budget follows the initialization timeout property.
                    assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(25));
                    // Command budget stays exactly the configured command timeout.
                    assertThat(factory.getNativeClient().getDefaultTimeout())
                            .isEqualTo(Duration.ofSeconds(2));
                    assertThat(factory.getTimeout()).isEqualTo(Duration.ofSeconds(2).toMillis());
                });
    }

    @Test
    void handshakeFailureIsBoundedByInitializationTimeoutNotCommandTimeout() throws Exception {
        // Reproduces the production failure mode against a listener that accepts
        // TCP and then stays silent: Lettuce completes TCP connect and channel
        // registration, so the only remaining bound is
        // RedisHandshakeHandler.initializeTimeout (<- RedisURI.getTimeout()).
        // Lettuce's synchronous connect() waits on the connection future without
        // applying the command timeout, so the reported budget must be the
        // configured initialization timeout (1s here), never the 2s command timeout.
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                while (!silent.isClosed()) {
                    try {
                        held.add(silent.accept());
                    } catch (Exception ignored) {
                        return;
                    }
                }
            }, "silent-redis-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            redisContext()
                    .withUserConfiguration(com.personal.jobagent.config.RedisLettuceConfiguration.class)
                    .withPropertyValues(
                            "spring.data.redis.host=127.0.0.1",
                            "spring.data.redis.port=" + silent.getLocalPort(),
                            "spring.data.redis.timeout=2s",
                            "spring.data.redis.connect-timeout=2s",
                            "app.redis.connection-initialization-timeout=1s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                        Throwable failure = catchThrowable(factory::getConnection);
                        assertThat(failure).isNotNull();
                        assertThat(causeMessages(failure))
                                .anySatisfy(message -> assertThat(message)
                                        .contains("Connection initialization timed out after 1 second(s)"));
                        assertThat(causeMessages(failure))
                                .noneSatisfy(message -> assertThat(message)
                                        .contains("Connection initialization timed out after 2 second(s)"));
                    });
        } finally {
            held.forEach(socket -> {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Test cleanup only.
                }
            });
        }
    }

    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return messages;
    }

    @Test
    void customFactoryAcquiresAndPingsAgainstLocalRedis() {
        Assumptions.assumeTrue(localRedisAvailable(), "local Docker Redis is not reachable");
        redisContext()
                .withUserConfiguration(com.personal.jobagent.config.RedisLettuceConfiguration.class)
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.timeout=2s",
                        "spring.data.redis.connect-timeout=2s",
                        "app.redis.connection-initialization-timeout=10s")
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
