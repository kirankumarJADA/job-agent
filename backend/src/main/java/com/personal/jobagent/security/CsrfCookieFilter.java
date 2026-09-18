package com.personal.jobagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces Spring Security 6's deferred CSRF token to materialize on every
 * request so the CookieCsrfTokenRepository always writes the XSRF-TOKEN
 * cookie.
 *
 * Without this, the cookie only appears if something happens to access the
 * token during the request — which never happens in this SPA's flow (login
 * is CSRF-exempt; subsequent GETs touch nothing CSRF-related), so the
 * frontend could never read a token to echo back in X-XSRF-TOKEN and every
 * mutating call would 403. Verified live during Feature 8 verification:
 * POST /api/v1/cover-letters/generate returned 403 with a valid session
 * until this filter landed. This is the fix documented in the Spring
 * Security docs ("CSRF Considerations for Single Page Applications").
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Accessing the token is what triggers the repository to write
            // the cookie on this response.
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
