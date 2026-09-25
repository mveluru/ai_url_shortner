package com.urlshortener.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the checked-in contract, byte for byte (design doc section 22.1-22.3). The Swagger UI is pointed at this file
 * instead of at a spec springdoc generates from annotations, so there is exactly one source of truth for the contract:
 * {@code docs/openapi.yaml}, copied onto the classpath by the build (section 22.6) with no transformation.
 *
 * <p>Present only where the UI is enabled: in the {@code prod} profile the bean does not exist and the route is simply
 * unmapped (section 22.4).
 */
@RestController
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true", matchIfMissing = true)
class OpenApiContractController {

    private final Resource contract = new ClassPathResource("openapi/openapi.yaml");

    @GetMapping(value = "/v3/api-docs.yaml", produces = "application/yaml")
    Resource serveContract() {
        return contract;
    }
}
