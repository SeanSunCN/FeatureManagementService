package com.flag.sdk.light;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration example for integrating LightFlagClient.
 *
 * Simply introduce this configuration class into your business project's Spring context,
 * and you can inject and use the FlagSdkClient interface via @Autowired.
 */
@Configuration
public class LightFlagClientConfig {

    @Bean(destroyMethod = "close")
    public LightFlagClient lightFlagClient(
            @Value("${flag.eval-service-url:http://localhost:8081}") String evalServiceUrl,
            @Value("${flag.default-result:false}") boolean defaultResult) {

        return new LightFlagClient(evalServiceUrl, defaultResult);
    }
}
