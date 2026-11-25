package com.distribuidora.identity.application;

import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Profile("!test")
public class BootstrapAdminRunner implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final boolean requirePasswordMatch;

    public BootstrapAdminRunner(
        UserAccountRepository users,
        PasswordEncoder passwordEncoder,
        @Value("${app.security.bootstrap-admin-email:}") String email,
        @Value("${app.security.bootstrap-admin-password:}") String password,
        @Value("${app.security.require-bootstrap-password-match:false}") boolean requirePasswordMatch
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.requirePasswordMatch = requirePasswordMatch;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            if (requirePasswordMatch) {
                throw new IllegalStateException("Administrator credentials are required for the tunnel");
            }
            return;
        }
        UserAccount user = users.findByEmailIgnoreCase(email)
            .orElseGet(() -> users.save(new UserAccount(email, passwordEncoder.encode(password))));
        if (requirePasswordMatch && !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalStateException("Rotate the existing administrator password locally before enabling the tunnel");
        }
        UUID roleId = users.findRoleId("ADMIN");
        users.assignRole(user.getId(), roleId);
    }
}
