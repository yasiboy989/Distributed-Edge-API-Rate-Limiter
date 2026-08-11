package com.ratelimiter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.security.AdminBootstrapKeyProvider;
import com.ratelimiter.security.ApiKeyAuthFilter;
import com.ratelimiter.service.KeyManagementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.net.URI;

/**
 * Stateless, X-API-Key-based auth (§7.4). No sessions, no cookies, no login
 * redirects — every failure is a JSON problem-detail body (§9).
 */
@Configuration
public class SecurityConfig {

    private final KeyManagementService keyManagementService;
    private final AdminBootstrapKeyProvider bootstrapKeyProvider;
    private final ObjectMapper objectMapper;

    public SecurityConfig(KeyManagementService keyManagementService,
                           AdminBootstrapKeyProvider bootstrapKeyProvider,
                           ObjectMapper objectMapper) {
        this.keyManagementService = keyManagementService;
        this.bootstrapKeyProvider = bootstrapKeyProvider;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .anonymous(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/prometheus",
                                "/swagger-ui/**", "/v3/api-docs/**", "/h2-console/**").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(missingApiKeyEntryPoint())
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.FORBIDDEN, "This API key does not have access to this operation");
                            problem.setTitle("Insufficient privileges");
                            problem.setInstance(URI.create(request.getRequestURI()));
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(), problem);
                        }))
                .addFilterBefore(
                        new ApiKeyAuthFilter(keyManagementService, bootstrapKeyProvider, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint missingApiKeyEntryPoint() {
        return (request, response, authException) -> {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED, "The X-API-Key header is required");
            problem.setTitle("Missing API key");
            problem.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), problem);
        };
    }
}
