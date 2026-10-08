package za.co.rockmission.ledger.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-in is a short sequence: password -> (set a new password if required) -> (enrol the authenticator app
 * the first time) -> 6-digit code -> session. Only the final step returns an access token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Login(@NotBlank String email, @NotBlank String password) {}
    public record Code(@NotBlank String code) {}
    public record NewPassword(@NotBlank String newPassword) {}
    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    static final String COOKIE = "rl_refresh";

    private final UserStore users;
    private final TokenService tokens;
    private final PasswordEncoder encoder;
    private final SecretBox box;
    private final JdbcTemplate jdbc;
    private final boolean cookieSecure;
    private final String dummyHash;

    public AuthController(UserStore users, TokenService tokens, PasswordEncoder encoder, SecretBox box,
                          JdbcTemplate jdbc, @Value("${ledger.cookie-secure:true}") boolean cookieSecure) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.box = box;
        this.jdbc = jdbc;
        this.cookieSecure = cookieSecure;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody Login in) {
        String email = in.email().trim().toLowerCase();
        AppUser u = users.byEmail(email);
        if (u == null) {
            encoder.matches(in.password(), dummyHash); // keep timing the same for unknown emails
            audit(email, "LOGIN_FAIL", "unknown email");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        checkNotLocked(u);
        if (!u.active() || !encoder.matches(in.password(), u.passwordHash())) {
            if (u.active()) users.recordFailure(u);
            audit(email, "LOGIN_FAIL", "wrong password");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        return next(u);
    }

    @PostMapping("/set-password")
    public Map<String, Object> setPassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewPassword in) {
        AppUser u = pendingUser(jwt, "PASSWORD");
        checkPolicy(in.newPassword(), u);
        if (encoder.matches(in.newPassword(), u.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different password from the temporary one");
        }
        users.setPassword(u.id(), encoder.encode(in.newPassword()), false);
        audit(u.email(), "PASSWORD_SET", "first login");
        return next(users.byId(u.id()));
    }

    @PostMapping("/enrol/start")
    public Map<String, Object> enrolStart(@AuthenticationPrincipal Jwt jwt) {
        AppUser u = pendingUser(jwt, "ENROL");
        if (u.totpEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Authenticator already set up");
        // Reuse an unconfirmed key so an entry already added to the phone keeps working after signing in again
        String secret = u.totpSecretEnc() != null ? box.open(u.totpSecretEnc()) : null;
        if (secret == null) {
            secret = Totp.newSecret();
            users.storeTotpSecret(u.id(), box.seal(secret));
        }
        return Map.of("secret", secret, "otpauthUri", Totp.uri("Rock Ledger", u.email(), secret));
    }

    @PostMapping("/enrol/confirm")
    public Map<String, Object> enrolConfirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Code in,
                                            HttpServletResponse res) {
        AppUser u = pendingUser(jwt, "ENROL");
        if (u.totpSecretEnc() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Start the setup first");
        long step = Totp.verify(box.open(u.totpSecretEnc()), in.code(), Instant.now().getEpochSecond(), -1);
        if (step < 0) {
            users.recordFailure(u);
            audit(u.email(), "2FA_FAIL", "enrol");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code is not right. Check the time on your phone and try again.");
        }
        users.enableTotp(u.id(), step);
        audit(u.email(), "2FA_ENROLLED", "");
        return session(u, res, true);
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Code in,
                                      HttpServletResponse res) {
        AppUser u = pendingUser(jwt, "TOTP");
        long last = u.lastTotpStep() == null ? -1 : u.lastTotpStep();
        long step = Totp.verify(box.open(u.totpSecretEnc()), in.code(), Instant.now().getEpochSecond(), last);
        if (step < 0) {
            users.recordFailure(u);
            audit(u.email(), "2FA_FAIL", "login");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code is not right");
        }
        users.markTotpUsed(u.id(), step);
        return session(u, res, true);
    }

    /** Silent re-login from the HttpOnly cookie. The cookie is single use and replaced on every call. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@CookieValue(name = COOKIE, required = false) String token,
                                       @RequestHeader(value = "X-Requested-With", required = false) String xrw,
                                       HttpServletResponse res) {
        requireXrw(xrw);
        Long uid = token == null ? null : tokens.consumeRefreshToken(token);
        AppUser u = uid == null ? null : users.byId(uid);
        if (u == null || !u.active()) {
            res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Signed out");
        }
        return session(u, res, false);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = COOKIE, required = false) String token,
                       @RequestHeader(value = "X-Requested-With", required = false) String xrw,
                       HttpServletResponse res) {
        requireXrw(xrw);
        tokens.revokeRefreshToken(token);
        res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePassword in,
                               Principal who) {
        if (jwt == null || !"access".equals(jwt.getClaimAsString("tt"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        AppUser u = users.byEmail(who.getName());
        if (u == null || !encoder.matches(in.currentPassword(), u.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is not right");
        }
        checkPolicy(in.newPassword(), u);
        users.setPassword(u.id(), encoder.encode(in.newPassword()), false);
        tokens.revokeAll(u.id()); // other devices must sign in again
        audit(u.email(), "PASSWORD_CHANGED", "");
    }

    // ---------------------------------------------------------------------------------------------

    private Map<String, Object> next(AppUser u) {
        if (u.mustChangePassword()) return Map.of("status", "CHANGE_PASSWORD", "pendingToken", tokens.pending(u, "PASSWORD"));
        if (!u.totpEnabled()) return Map.of("status", "ENROL", "pendingToken", tokens.pending(u, "ENROL"));
        return Map.of("status", "TOTP", "pendingToken", tokens.pending(u, "TOTP"));
    }

    private Map<String, Object> session(AppUser u, HttpServletResponse res, boolean fresh) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(tokens.newRefreshToken(u.id()), TokenService.REFRESH).toString());
        if (fresh) {
            users.recordSuccess(u.id());
            audit(u.email(), "LOGIN_OK", "");
        }
        return Map.of(
            "accessToken", tokens.access(u),
            "expiresIn", TokenService.ACCESS.toSeconds(),
            "user", Map.of("email", u.email(), "name", u.fullName(), "role", u.role()));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
            .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/api/auth").maxAge(maxAge).build();
    }

    private AppUser pendingUser(Jwt jwt, String purpose) {
        if (jwt == null || !"pending".equals(jwt.getClaimAsString("tt"))
                || !purpose.equals(jwt.getClaimAsString("purpose"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Please sign in again");
        }
        AppUser u = users.byId(((Number) jwt.getClaim("uid")).longValue());
        if (u == null || !u.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Please sign in again");
        checkNotLocked(u);
        return u;
    }

    private void checkNotLocked(AppUser u) {
        if (u.lockedUntil() != null && u.lockedUntil().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed attempts. Try again in " + UserStore.LOCK_MINUTES + " minutes.");
        }
    }

    /** Cross-site pages cannot send this header without a CORS preflight, which the API refuses. */
    private void requireXrw(String header) {
        if (header == null || header.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    static void checkPolicy(String password, AppUser u) {
        if (password.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use at least 12 characters (a few random words works well)");
        }
        if (password.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is too long");
        if (password.equalsIgnoreCase(u.email()) || password.chars().distinct().count() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That password is too easy to guess");
        }
    }

    private void audit(String actor, String action, String detail) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity, detail) VALUES (?,?,?,?)", actor, action, "auth", detail);
    }
}
