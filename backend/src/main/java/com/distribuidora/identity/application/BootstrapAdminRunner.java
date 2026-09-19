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

@Component
@Profile("!test")
public class BootstrapAdminRunner implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public BootstrapAdminRunner(
        UserAccountRepository users,
        PasswordEncoder passwordEncoder,
        @Value("${app.security.bootstrap-admin-email:}") String email,
        @Value("${app.security.bootstrap-admin-password:}") String password
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank() || users.findByEmailIgnoreCase(email).isPresent()) {
            return;
        }
        users.save(new UserAccount(email, passwordEncoder.encode(password)));
    }
}
