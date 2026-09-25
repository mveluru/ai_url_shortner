package com.urlshortener.common.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JacksonConfig {

    /**
     * A JSON number/boolean where a string is expected (e.g. {@code "expiresAt": 1893456000} or {@code "longUrl": 12345})
     * is a wrong-typed field and must be MALFORMED_JSON (design doc section 9.2), not silently stringified into a value
     * that later fails a business rule with a misleading code. Jackson's {@code ALLOW_COERCION_OF_SCALARS} does not cover
     * scalar-to-String, so the coercion is refused for textual targets explicitly.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictStringCoercion() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
