package com.urlshortener.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.common.error.AliasReservedException;
import com.urlshortener.common.error.AliasTakenException;
import com.urlshortener.common.error.ErrorCode;
import com.urlshortener.common.error.ForbiddenException;
import com.urlshortener.common.error.GlobalExceptionHandler;
import com.urlshortener.common.error.InvalidAliasException;
import com.urlshortener.common.error.InvalidExpiryException;
import com.urlshortener.common.error.InvalidRangeException;
import com.urlshortener.common.error.InvalidUrlException;
import com.urlshortener.common.error.RateLimitedException;
import com.urlshortener.common.error.ResourceModifiedException;
import com.urlshortener.common.error.ResourceNotFoundException;
import com.urlshortener.common.error.ServiceUnavailableException;
import com.urlshortener.common.error.UnauthorizedException;
import com.urlshortener.common.error.UpstreamTimeoutException;
import com.urlshortener.common.web.RequestIdFilter;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestProps;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every row of the design doc's section 9.2 mapping table and section 9.3 handler table, as a pure unit test (no Docker):
 * one exception in, one status + code out, always in the one error shape with a requestId.
 */
class GlobalExceptionHandlerTest {

    record Body(@NotBlank String longUrl, @Size(min = 3, message = "too short") String customAlias,
                @Size(max = 10) String note) {}

    @RestController
    static class ThrowingController {

        @GetMapping("/throw/{kind}")
        String boom(@PathVariable String kind) throws Exception {
            throw switch (kind) {
                case "invalid-url" -> new InvalidUrlException("bad url");
                case "invalid-alias" -> new InvalidAliasException("bad alias");
                case "alias-reserved" -> new AliasReservedException("reserved");
                case "invalid-expiry" -> new InvalidExpiryException("bad expiry");
                case "invalid-range" -> new InvalidRangeException("bad range");
                case "unauthorized" -> new UnauthorizedException("no key");
                case "forbidden" -> new ForbiddenException("not yours");
                case "not-found" -> new ResourceNotFoundException("nope");
                case "alias-taken" -> new AliasTakenException("taken");
                case "resource-modified" -> new ResourceModifiedException("changed");
                case "rate-limited" -> new RateLimitedException("slow down", 7);
                case "upstream-timeout" -> new UpstreamTimeoutException("slow dependency", new TimeoutException());
                case "service-unavailable" -> new ServiceUnavailableException("down");
                case "dive-short-code" -> new DataIntegrityViolationException("dup",
                        new org.hibernate.exception.ConstraintViolationException("dup",
                                new SQLIntegrityConstraintViolationException("Duplicate entry 'x' for key 'urls.uk_urls_short_code'"),
                                "urls.uk_urls_short_code"));
                case "dive-other" -> new DataIntegrityViolationException("Duplicate entry 'x' for key 'urls.something_else'");
                case "optimistic" -> new OptimisticLockingFailureException("version mismatch");
                case "circuit-open" -> {
                    CircuitBreaker cb = CircuitBreaker.ofDefaults("mysql-read");
                    cb.transitionToOpenState();
                    yield CallNotPermittedException.createCallNotPermittedException(cb);
                }
                case "timeout" -> new TimeoutException("took too long");
                case "query-timeout" -> new QueryTimeoutException("slow query");
                case "db-down" -> new DataAccessResourceFailureException("Communications link failure jdbc:mysql://secret-host");
                case "tx-down" -> new CannotCreateTransactionException("Could not open JPA EntityManager");
                case "access-denied" -> new AccessDeniedException("denied");
                case "constraint-violation" -> new ConstraintViolationException(
                        Validation.buildDefaultValidatorFactory().getValidator().validate(new Body("", "x", null)));
                case "unexpected" -> new IllegalStateException("java.sql.SQLException: SELECT * FROM urls; password=hunter2");
                default -> new IllegalArgumentException(kind);
            };
        }

        @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        String validate(@Valid @RequestBody Body body) {
            return "{}";
        }

        @GetMapping("/stats")
        String stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) Integer limit) {
            return "{}";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        GlobalExceptionHandler advice = new GlobalExceptionHandler(
                Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry(), TestProps.defaults());
        // Boot's own ObjectMapper writes java.time values as ISO-8601 strings; a bare standalone mapper would write numbers.
        var mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(advice)
                .setValidator(validator)
                .addDispatcherServletCustomizer(ds -> ds.setThrowExceptionIfNoHandlerFound(true))
                .addFilters(new RequestIdFilter(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(io.micrometer.tracing.Tracer.class)))
                .build();
    }

    static Stream<Arguments> domainMapping() {
        return Stream.of(
                Arguments.of("invalid-url", 400, ErrorCode.INVALID_URL),
                Arguments.of("invalid-alias", 400, ErrorCode.INVALID_ALIAS),
                Arguments.of("alias-reserved", 400, ErrorCode.ALIAS_RESERVED),
                Arguments.of("invalid-expiry", 400, ErrorCode.INVALID_EXPIRY),
                Arguments.of("invalid-range", 400, ErrorCode.INVALID_RANGE),
                Arguments.of("unauthorized", 401, ErrorCode.UNAUTHORIZED),
                Arguments.of("forbidden", 403, ErrorCode.FORBIDDEN),
                Arguments.of("not-found", 404, ErrorCode.NOT_FOUND),
                Arguments.of("alias-taken", 409, ErrorCode.ALIAS_TAKEN),
                Arguments.of("resource-modified", 409, ErrorCode.RESOURCE_MODIFIED),
                Arguments.of("rate-limited", 429, ErrorCode.RATE_LIMITED),
                Arguments.of("upstream-timeout", 504, ErrorCode.UPSTREAM_TIMEOUT),
                Arguments.of("service-unavailable", 503, ErrorCode.SERVICE_UNAVAILABLE));
    }

    @ParameterizedTest(name = "s9.3 domain exception {0} -> {1} {2}")
    @MethodSource("domainMapping")
    void domainExceptionsMapToExactlyOneRow(String kind, int status, ErrorCode code) throws Exception {
        mvc.perform(get("/throw/" + kind))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value("2026-09-25T10:00:00Z"))
                .andExpect(jsonPath("$.path").value("/throw/" + kind))
                .andExpect(header().string("Content-Type", "application/json"));
    }

    static Stream<Arguments> infrastructureMapping() {
        return Stream.of(
                Arguments.of("dive-short-code", 409, ErrorCode.ALIAS_TAKEN),
                Arguments.of("dive-other", 500, ErrorCode.INTERNAL_ERROR),
                Arguments.of("optimistic", 409, ErrorCode.RESOURCE_MODIFIED),
                Arguments.of("circuit-open", 503, ErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("timeout", 504, ErrorCode.UPSTREAM_TIMEOUT),
                Arguments.of("query-timeout", 504, ErrorCode.UPSTREAM_TIMEOUT),
                Arguments.of("db-down", 503, ErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("tx-down", 503, ErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("access-denied", 403, ErrorCode.FORBIDDEN),
                Arguments.of("constraint-violation", 400, ErrorCode.VALIDATION_FAILED),
                Arguments.of("unexpected", 500, ErrorCode.INTERNAL_ERROR));
    }

    @ParameterizedTest(name = "s9.3 infrastructure exception {0} -> {1} {2}")
    @MethodSource("infrastructureMapping")
    void infrastructureExceptionsMapPerTheTable(String kind, int status, ErrorCode code) throws Exception {
        mvc.perform(get("/throw/" + kind))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("s9.4: 5xx bodies never leak stack traces, SQL, hostnames, credentials or exception class names")
    void noInternalsInBodies() throws Exception {
        for (String kind : new String[] {"unexpected", "db-down", "tx-down", "dive-other", "circuit-open", "timeout"}) {
            String body = mvc.perform(get("/throw/" + kind)).andReturn().getResponse().getContentAsString();
            assertThat(body).as(kind).doesNotContain("SELECT").doesNotContain("hunter2").doesNotContain("secret-host")
                    .doesNotContain("jdbc").doesNotContain("Exception").doesNotContain("java.").doesNotContain("org.");
        }
    }

    @Test
    @Covers({"E19", "F3"})
    @DisplayName("s9.2: RATE_LIMITED carries Retry-After; SERVICE_UNAVAILABLE (incl. an open breaker) carries Retry-After too")
    void retryAfterHeaders() throws Exception {
        mvc.perform(get("/throw/rate-limited")).andExpect(header().string("Retry-After", "7"));
        mvc.perform(get("/throw/service-unavailable")).andExpect(header().string("Retry-After", "5"));
        mvc.perform(get("/throw/circuit-open")).andExpect(header().string("Retry-After", "5"));
        mvc.perform(get("/throw/db-down")).andExpect(header().string("Retry-After", "5"));
    }

    @Test
    @DisplayName("s9.2a: VALIDATION_FAILED reports EVERY violated field at once, with field, constraint, message and rejected value")
    void validationFailedShape() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"\",\"customAlias\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='longUrl')].constraint").value("NotBlank"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='customAlias')].constraint").value("Size"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='customAlias')].message").value("too short"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='customAlias')].rejectedValue").value("x"));
    }

    @Test
    @DisplayName("s9.2a: an enormous rejected value is truncated in the echo (the body must not become a reflection channel)")
    void rejectedValueIsTruncated() throws Exception {
        String body = mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://x.test\",\"note\":\"" + "a".repeat(5_000) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body.length()).as("5,000 characters were rejected but must not be echoed back").isLessThan(1_000);
        assertThat(body).contains("aaaa...");
    }

    @Test
    @DisplayName("s9.2: MALFORMED_JSON, 415, 406, 405 and NO_HANDLER all use the standard body")
    void springMvcExceptions() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mvc.perform(post("/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML).content("{}"))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        mvc.perform(patch("/validate")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED")).andExpect(header().exists("Allow"));
        mvc.perform(get("/no/such/route")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_HANDLER"));
    }

    @Test
    @Covers({"E22"})
    @DisplayName("s9.2: a malformed 'from'/'to' date is INVALID_RANGE; any other type mismatch is VALIDATION_FAILED")
    void typeMismatches() throws Exception {
        mvc.perform(get("/stats").param("from", "not-a-date"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/stats").param("to", "2026-13-45"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/stats").param("limit", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
    }

    @Test
    @DisplayName("every ErrorCode in the taxonomy has a status, and none is missing from the design's table of 20 codes")
    void taxonomyIsComplete() {
        assertThat(ErrorCode.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "VALIDATION_FAILED", "INVALID_URL", "INVALID_ALIAS", "ALIAS_RESERVED", "INVALID_EXPIRY", "INVALID_RANGE",
                "MALFORMED_JSON", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "NO_HANDLER", "METHOD_NOT_ALLOWED", "NOT_ACCEPTABLE",
                "ALIAS_TAKEN", "RESOURCE_MODIFIED", "UNSUPPORTED_MEDIA_TYPE", "RATE_LIMITED", "INTERNAL_ERROR",
                "SERVICE_UNAVAILABLE", "UPSTREAM_TIMEOUT");
    }
}
