package com.personal.jobagent.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorHealthConfigurationTest {

    @Test
    void aggregateHealthExposesComponentStatusesButNeverDetails() {
        Properties properties = load("application.yml");

        assertThat(properties.getProperty("management.endpoint.health.show-components"))
                .isEqualTo("always");
        assertThat(properties.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void productionHealthConfigurationUsesTheSameSafeDiagnosticPolicy() {
        Properties properties = load("application-prod.yml");

        assertThat(properties.getProperty("management.endpoint.health.show-components"))
                .isEqualTo("always");
        assertThat(properties.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    private Properties load(String resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        yaml.afterPropertiesSet();
        return yaml.getObject();
    }
}
