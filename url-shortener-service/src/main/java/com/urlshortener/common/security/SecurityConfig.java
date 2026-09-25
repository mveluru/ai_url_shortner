package com.urlshortener.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless API-key security (design doc section 10.1, 20.1). {@code /api/**} requires a valid key; everything else -
 * crucially the redirect endpoint - is {@code permitAll()} and never touches the auth service (section 8.1).
 */
@Configuration
public class SecurityConfig {

    @Bean
    ApiKeyAuthFilter apiKeyAuthFilter(ApiKeyService keys,
                                      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptions) {
        return new ApiKeyAuthFilter(keys, exceptions);
    }

    /** The filter runs inside the security chain only; stop Boot also registering it as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Firewall rejections (E18) go through the one error handler instead of the container's default 400 page. */
    @Bean
    RequestRejectedHandler requestRejectedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptions) {
        return (request, response, ex) -> exceptions.resolveException(request, response, null, ex);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyAuthFilter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptions) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)                      // stateless, header-key API: no cookies to forge
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .headers(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> exceptions.resolveException(req, res, null, ex))
                        .accessDeniedHandler((req, res, ex) -> exceptions.resolveException(req, res, null, ex)))
                .addFilterBefore(apiKeyAuthFilter, AuthorizationFilter.class);
        return http.build();
    }
}
