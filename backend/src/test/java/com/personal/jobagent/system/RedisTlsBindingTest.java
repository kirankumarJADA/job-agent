package com.personal.jobagent.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
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
