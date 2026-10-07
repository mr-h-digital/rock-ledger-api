package za.co.rockmission.ledger.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class UserStore {
    static final int MAX_FAILED = 5;
    static final int LOCK_MINUTES = 15;

    private final JdbcTemplate jdbc;

    private static final RowMapper<AppUser> MAP = (rs, n) -> new AppUser(
        rs.getLong("id"), rs.getString("email"), rs.getString("full_name"), rs.getString("role"),
        rs.getString("password_hash"), rs.getBoolean("must_change_password"),
        rs.getString("totp_secret_enc"), rs.getBoolean("totp_enabled"),
        rs.getObject("last_totp_step", Long.class), rs.getBoolean("active"),
        rs.getInt("failed_attempts"),
        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant(),
        rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant());

    public UserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AppUser byEmail(String email) {
        List<AppUser> l = jdbc.query("SELECT * FROM users WHERE email = ?", MAP, email);
        return l.isEmpty() ? null : l.get(0);
    }

    public AppUser byId(long id) {
        List<AppUser> l = jdbc.query("SELECT * FROM users WHERE id = ?", MAP, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<AppUser> list() {
        return jdbc.query("SELECT * FROM users ORDER BY full_name", MAP);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        return n == null ? 0 : n;
    }

    public int activeAdmins() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN' AND active", Integer.class);
        return n == null ? 0 : n;
    }

    public long create(String email, String fullName, String role, String passwordHash) {
        return jdbc.queryForObject("""
            INSERT INTO users (email, full_name, role, password_hash, must_change_password)
            VALUES (?,?,?,?,TRUE) RETURNING id
            """, Long.class, email, fullName, role, passwordHash);
    }

    public void recordFailure(AppUser u) {
        int attempts = u.failedAttempts() + 1;
        if (attempts >= MAX_FAILED) {
            jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES)), u.id());
        } else {
            jdbc.update("UPDATE users SET failed_attempts = ? WHERE id = ?", attempts, u.id());
        }
    }

    public void recordSuccess(long id) {
        jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE id = ?", id);
    }

    public void setPassword(long id, String hash, boolean mustChange) {
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?", hash, mustChange, id);
    }

    public void storeTotpSecret(long id, String sealed) {
        jdbc.update("UPDATE users SET totp_secret_enc = ?, totp_enabled = FALSE, last_totp_step = NULL WHERE id = ?", sealed, id);
    }

    public void enableTotp(long id, long step) {
        jdbc.update("UPDATE users SET totp_enabled = TRUE, last_totp_step = ? WHERE id = ?", step, id);
    }

    public void markTotpUsed(long id, long step) {
        jdbc.update("UPDATE users SET last_totp_step = ? WHERE id = ?", step, id);
    }

    public void resetSecurity(long id, String hash) {
        jdbc.update("""
            UPDATE users SET password_hash = ?, must_change_password = TRUE, totp_secret_enc = NULL,
                totp_enabled = FALSE, last_totp_step = NULL, failed_attempts = 0, locked_until = NULL
            WHERE id = ?""", hash, id);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE users SET active = ? WHERE id = ?", active, id);
    }
}
