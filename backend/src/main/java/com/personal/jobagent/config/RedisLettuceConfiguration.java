package com.personal.jobagent.config;

import io.lettuce.core.resource.DnsResolvers;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedisLettuceConfiguration {
    @Bean
    public ClientResourcesBuilderCustomizer redisJvmDnsResolver() {
        return builder -> builder.dnsResolver(DnsResolvers.JVM_DEFAULT);
    }
}
