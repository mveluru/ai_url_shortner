package com.urlshortener.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The authenticated principal for a management-API request: just the owning key id. */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final String keyId;

    public ApiKeyAuthentication(String keyId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_API")));
        this.keyId = keyId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return keyId;
    }

    public String keyId() {
        return keyId;
    }
}
