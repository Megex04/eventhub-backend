package com.eventhub.catalog.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 Resource Server. Only the reads the OpenAPI marks with {@code security: []} are
 * public; everything else under {@code /api/v1} needs the ORGANIZER role. Ownership of a concrete
 * event is a business rule and is enforced by the use cases, not here.
 */
@Configuration
class SecurityConfig {

  static final String ORGANIZER_ROLE = "ORGANIZER";

  @Bean
  SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems)
      throws Exception {
    return http
        // Bearer tokens are not ambient credentials, so there is no cookie session to forge
        // requests on.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests ->
            requests
                .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/{eventId}")
                .permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info")
                .permitAll()
                .requestMatchers("/api/v1/**")
                .hasRole(ORGANIZER_ROLE)
                .anyRequest()
                .authenticated())
        .exceptionHandling(
            handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems))
        .oauth2ResourceServer(resourceServer ->
            resourceServer
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .build();
  }

  private static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());
    return converter;
  }
}
