package com.personal.jobagent.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;

/**
 * Adapts UserRecord to Spring Security's UserDetails contract.
 * Phase 18 Security Closure: provides role-based authorities (CANDIDATE, OPERATOR, ADMIN).
 *
 * <p>Ordinary candidates receive {@code ROLE_USER}, {@code ROLE_CANDIDATE}, and {@code USER}.
 * Authorized operators/administrators additionally receive {@code ROLE_OPERATOR}, {@code ROLE_ADMIN},
 * {@code OPERATOR}, and {@code ADMIN}.
 */
public class AppUserDetails implements UserDetails {

    private final UserRecord user;
    private final boolean operator;

    public AppUserDetails(UserRecord user) {
        this(user, user.isOperator());
    }

    public AppUserDetails(UserRecord user, boolean operator) {
        this.user = user;
        this.operator = operator || user.isOperator();
    }

    public UUID getUserId() {
        return user.id();
    }

    public String getDisplayName() {
        return user.displayName();
    }

    public boolean isOperator() {
        return operator;
    }

    public String getRole() {
        return operator ? (user.role() != null && "ADMIN".equalsIgnoreCase(user.role()) ? "ADMIN" : "OPERATOR") : "CANDIDATE";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (operator) {
            return AuthorityUtils.createAuthorityList(
                    "ROLE_USER", "ROLE_CANDIDATE", "USER", "ROLE_OPERATOR", "ROLE_ADMIN", "OPERATOR", "ADMIN");
        }
        return AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_CANDIDATE", "USER");
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.email();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
