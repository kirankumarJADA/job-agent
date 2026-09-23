package com.personal.jobagent.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.springframework.util.StringUtils.hasText;

@Configuration(proxyBeanMethods = false)
public class RedisLettuceConfiguration {
    public ClientResourcesBuilderCustomizer redisJvmDnsResolver() {
        return builder -> builder.dnsResolver(io.lettuce.core.resource.DnsResolvers.JVM_DEFAULT);
    }

    @Bean(destroyMethod = "shutdown")
    public ClientResources redisClientResources() {
        io.lettuce.core.resource.DefaultClientResources.Builder builder =
                io.lettuce.core.resource.DefaultClientResources.builder();
        redisJvmDnsResolver().customize(builder);
        return builder.build();
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            RedisProperties properties,
            ClientResources clientResources,
            @Value("${app.redis.connection-initialization-timeout:10s}") String initializationTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                properties.getHost(), properties.getPort());
        standalone.setDatabase(properties.getDatabase());
        if (hasText(properties.getUsername())) {
            standalone.setUsername(properties.getUsername());
        }
        if (hasText(properties.getPassword())) {
            standalone.setPassword(RedisPassword.of(properties.getPassword()));
        }

        Duration commandTimeout = properties.getTimeout() == null
                ? Duration.ofSeconds(2) : properties.getTimeout();
        Duration shutdownTimeout = properties.getLettuce().getShutdownTimeout();
        ClientOptions.Builder optionsBuilder = ClientOptions.builder();
        if (properties.getConnectTimeout() != null) {
            optionsBuilder.socketOptions(SocketOptions.builder()
                    .connectTimeout(properties.getConnectTimeout())
                    .build());
        }
        ClientOptions options = optionsBuilder
                .timeoutOptions(TimeoutOptions.enabled())
                .build();

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder()
                        .clientResources(clientResources)
                        .clientOptions(options);
        builder.commandTimeout(commandTimeout);
        if (properties.getSsl().isEnabled()) {
            builder.useSsl().and();
        }
        if (shutdownTimeout != null && !shutdownTimeout.isZero()) {
            builder.shutdownTimeout(shutdownTimeout);
        }
        if (hasText(properties.getClientName())) {
            builder.clientName(properties.getClientName());
        }
        return new InitializationTimeoutLettuceConnectionFactory(
                standalone, builder.build(), DurationStyle.detectAndParse(initializationTimeout));
    }

    /**
     * Spring Data Redis uses the command timeout as Lettuce's client default
     * timeout. Lettuce then also uses that default for the initial Redis
     * handshake, which couples a 2-second command budget to a cold TLS
     * connection. Override only the client default used by the synchronous
     * shared-native connection; LettuceConnection still receives the original
     * command timeout from the factory configuration.
     */
    public static final class InitializationTimeoutLettuceConnectionFactory extends LettuceConnectionFactory {
        private final Duration initializationTimeout;

        InitializationTimeoutLettuceConnectionFactory(
                RedisStandaloneConfiguration configuration,
                LettuceClientConfiguration clientConfiguration,
                Duration initializationTimeout) {
            super(configuration, clientConfiguration);
            this.initializationTimeout = initializationTimeout;
        }

        @Override
        protected io.lettuce.core.AbstractRedisClient createClient() {
            io.lettuce.core.AbstractRedisClient client = super.createClient();
            client.setDefaultTimeout(initializationTimeout);
            return client;
        }

        public Duration getInitializationTimeout() {
            return initializationTimeout;
        }
    }
}
