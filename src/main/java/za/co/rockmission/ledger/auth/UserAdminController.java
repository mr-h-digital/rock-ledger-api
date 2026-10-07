package za.co.rockmission.ledger.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only user management. Temporary passwords are shown once and must be changed at first sign-in. */
@RestController
@RequestMapping("/api/users")
public class UserAdminController {

    public record NewUser(@NotBlank @Email String email, @NotBlank @Size(max = 120) String fullName,
                          @Pattern(regexp = "ADMIN|TREASURER|VIEWER") String role) {}
    public record Active(boolean active) {}

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom rng = new SecureRandom();

    private final UserStore users;
    private final TokenService tokens;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;

    public UserAdminController(UserStore users, TokenService tokens, PasswordEncoder encoder, JdbcTemplate jdbc) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return users.list().stream().map(UserAdminController::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody NewUser in, Principal who) {
        String email = in.email().trim().toLowerCase();
        if (users.byEmail(email) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "That email already has an account");
        String temp = temporaryPassword();
        long id = users.create(email, in.fullName().trim(), in.role(), encoder.encode(temp));
        audit(who.getName(), "USER_CREATED", id, email + " as " + in.role());
        return Map.of("user", view(users.byId(id)), "temporaryPassword", temp);
    }

    /** Forgotten password or lost phone: new temporary password, authenticator must be set up again. */
    @PostMapping("/{id}/reset")
    public Map<String, Object> reset(@PathVariable long id, Principal who) {
        AppUser u = require(id);
        String temp = temporaryPassword();
        users.resetSecurity(id, encoder.encode(temp));
        tokens.revokeAll(id);
        audit(who.getName(), "USER_RESET", id, u.email());
        return Map.of("user", view(users.byId(id)), "temporaryPassword", temp);
    }

    @PatchMapping("/{id}/active")
    public Map<String, Object> setActive(@PathVariable long id, @RequestBody Active in, Principal who) {
        AppUser u = require(id);
        if (!in.active()) {
            if (u.email().equals(who.getName())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot deactivate yourself");
            if (u.role().equals("ADMIN") && u.active() && users.activeAdmins() <= 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There must be at least one active admin");
            }
            tokens.revokeAll(id);
        }
        users.setActive(id, in.active());
        audit(who.getName(), in.active() ? "USER_ACTIVATED" : "USER_DEACTIVATED", id, u.email());
        return view(users.byId(id));
    }

    private AppUser require(long id) {
        AppUser u = users.byId(id);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return u;
    }

    private String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static Map<String, Object> view(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("email", u.email());
        m.put("fullName", u.fullName());
        m.put("role", u.role());
        m.put("active", u.active());
        m.put("twoFactor", u.totpEnabled());
        m.put("lastLogin", u.lastLoginAt() == null ? null : u.lastLoginAt().toString());
        return m;
    }

    private void audit(String actor, String action, long id, String detail) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity, entity_id, detail) VALUES (?,?,?,?,?)",
            actor, action, "user", id, detail);
    }
}
