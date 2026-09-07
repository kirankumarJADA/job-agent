package com.personal.jobagent.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;

/**
 * Adapts UserRecord to Spring Security's UserDetails contract. Phase 1 is
 * single-user with no RBAC (per the architecture's deliberate
 * simplification, §A4) — everyone gets a single fixed "USER" authority,
 * kept only so RBAC can be retrofitted later without changing this
 * adapter's shape.
 */
public class AppUserDetails implements UserDetails {

    private final UserRecord user;

    public AppUserDetails(UserRecord user) {
        this.user = user;
    }

    public UUID getUserId() {
        return user.id();
    }

    public String getDisplayName() {
        return user.displayName();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("USER");
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
