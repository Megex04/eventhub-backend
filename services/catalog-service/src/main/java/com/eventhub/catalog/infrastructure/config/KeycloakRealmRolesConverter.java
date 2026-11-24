package com.eventhub.catalog.infrastructure.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns the realm roles Keycloak puts in {@code realm_access.roles} into {@code ROLE_*}
 * authorities. A token without that claim, or with a malformed one, simply grants nothing: the
 * request then fails authorization instead of the converter failing authentication.
 */
final class KeycloakRealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final String REALM_ACCESS_CLAIM = "realm_access";
  private static final String ROLES_KEY = "roles";
  private static final String ROLE_PREFIX = "ROLE_";

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
    if (!(realmAccess instanceof Map<?, ?> claim)
        || !(claim.get(ROLES_KEY) instanceof List<?> roles)) {
      return List.of();
    }
    return roles.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(role -> !role.isBlank())
        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
        .toList();
  }
}
