package com.personal.jobagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates whether an authenticated user holds operational privileges for
 * discovery and source management (Phase 18 Security Closure).
 *
 * <p>Privileged operator access is derived from:
 * <ol>
 *   <li>The persisted user role in {@code users.role} ({@code OPERATOR} or {@code ADMIN}), or</li>
 *   <li>Configured operator email addresses in {@code app.security.operator-emails}
 *       (e.g., via the {@code ROBIN_OPERATOR_EMAILS} environment variable in production).</li>
 * </ol>
 *
 * <p>Security guarantees:
 * <ul>
 *   <li>Self-service registration always assigns {@code CANDIDATE} role.</li>
 *   <li>No client request body or header can specify or elevate roles.</li>
 *   <li>Existing users default to {@code CANDIDATE} (no silent elevation).</li>
 *   <li>No hardcoded shared operator passwords or secrets.</li>
 * </ul>
 */
@Component
public class OperatorAccessPolicy {

    private final Set<String> operatorEmails;

    public OperatorAccessPolicy(@Value("${app.security.operator-emails:}") String emailsConfig) {
        if (emailsConfig == null || emailsConfig.isBlank()) {
            this.operatorEmails = Collections.emptySet();
        } else {
            this.operatorEmails = Arrays.stream(emailsConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public boolean isOperator(UserRecord user) {
        if (user == null) return false;
        if (user.isOperator()) return true;
        if (user.email() == null) return false;
        return operatorEmails.contains(user.email().trim().toLowerCase(Locale.ROOT));
    }

    public boolean isOperator(String email, String role) {
        if (role != null) {
            String r = role.trim().toUpperCase(Locale.ROOT);
            if ("OPERATOR".equals(r) || "ADMIN".equals(r)) return true;
        }
        if (email == null) return false;
        return operatorEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
