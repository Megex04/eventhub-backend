package com.eventhub.booking.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 Resource Server. Any authenticated user may book (no special role); only the
 * reads the OpenAPI marks with {@code security: []} are public. Ownership of a booking is a
 * business rule enforced by the use cases, not here.
 */
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
        // Bearer tokens are not ambient credentials, so there is no cookie session to forge
        // requests on.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests ->
            requests
                .requestMatchers(HttpMethod.GET, "/api/v1/availability/{eventId}")
                .permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}))
        .build();
  }
}
