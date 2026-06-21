package com.flag.sdk.light;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration example for integrating LightFlagClient.
 *
 * Simply introduce this configuration class into your business project's Spring context,
 * and you can inject and use the FlagSdkClient interface via @Autowired.
 *
 * Usage example:
 * <pre>{@code
 * @Service
 * public class CheckoutService {
 *     private final FlagSdkClient flagClient;
 *
 *     public CheckoutService(FlagSdkClient flagClient) {
 *         this.flagClient = flagClient;
 *     }
 *
 *     public void checkout(String userId) {
 *         if (flagClient.isEnabled("my-app", "new-checkout-flow", userId)) {
 *             // New checkout flow
 *         } else {
 *             // Old checkout flow
 *         }
 *     }
 * }
 * }</pre>
 */
@Configuration
public class LightFlagClientConfig {

    @Bean(destroyMethod = "close")
    public LightFlagClient lightFlagClient(
            @Value("${flag.app-id}") String appId,
            @Value("${flag.eval-service-url:http://localhost:8081}") String evalServiceUrl,
            @Value("${flag.ingest-service-url:http://localhost:8082}") String ingestServiceUrl,
            @Value("${flag.default-result:false}") boolean defaultResult) {

        return new LightFlagClient(appId, evalServiceUrl, ingestServiceUrl, defaultResult);
    }
}
