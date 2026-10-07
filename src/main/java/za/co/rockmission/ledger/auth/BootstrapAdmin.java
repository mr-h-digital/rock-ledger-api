package za.co.rockmission.ledger.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin when the users table is empty, from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD.
 * That password is only good for the first sign-in: the app forces a new one, then the authenticator set-up.
 * Remove both variables once you have signed in.
 */
@Component
class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserStore users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String name;
    private final String password;

    BootstrapAdmin(UserStore users, PasswordEncoder encoder,
                   @Value("${ledger.bootstrap-admin-email:}") String email,
                   @Value("${ledger.bootstrap-admin-name:Administrator}") String name,
                   @Value("${ledger.bootstrap-admin-password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email;
        this.name = name;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        if (email.isBlank() || password.length() < 12) {
            log.warn("No users exist. Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD (12+ characters) and restart.");
            return;
        }
        users.create(email.trim().toLowerCase(), name, "ADMIN", encoder.encode(password));
        log.info("Created first admin {}. Sign in, choose a new password and set up the authenticator app.", email);
    }
}
