package za.co.rockmission.ledger.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * access token  : 15 minutes, carries the role, kept in browser memory only
 * pending token : 5 minutes, only good for the next login step (set password / enrol / enter code)
 * refresh token : 12 hours, random, stored hashed, delivered in an HttpOnly cookie and rotated on every use
 */
@Service
public class TokenService {
    public static final Duration ACCESS = Duration.ofMinutes(15);
    public static final Duration PENDING = Duration.ofMinutes(5);
    public static final Duration REFRESH = Duration.ofHours(12);

    private final JwtEncoder encoder;
    private final JdbcTemplate jdbc;
    private final SecureRandom rng = new SecureRandom();

    public TokenService(JwtEncoder encoder, JdbcTemplate jdbc) {
        this.encoder = encoder;
        this.jdbc = jdbc;
    }

    public String access(AppUser u) {
        return encode(u, ACCESS, Map.of("tt", "access", "roles", List.of(u.role())));
    }

    public String pending(AppUser u, String purpose) {
        return encode(u, PENDING, Map.of("tt", "pending", "purpose", purpose));
    }

    private String encode(AppUser u, Duration ttl, Map<String, Object> extra) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder()
            .issuer("rock-ledger").subject(u.email()).issuedAt(now).expiresAt(now.plus(ttl)).claim("uid", u.id());
        extra.forEach((k, v) -> b.claim(k, v));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), b.build()))
            .getTokenValue();
    }

    public String newRefreshToken(long userId) {
        jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < now()");
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?,?,?)",
            userId, sha256(token), Timestamp.from(Instant.now().plus(REFRESH)));
        return token;
    }

    /** Single use: deletes the token and returns its user id, or null if unknown or expired. */
    public Long consumeRefreshToken(String token) {
        List<Long> ids = jdbc.queryForList(
            "DELETE FROM refresh_tokens WHERE token_hash = ? AND expires_at > now() RETURNING user_id",
            Long.class, sha256(token));
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void revokeRefreshToken(String token) {
        if (token != null) jdbc.update("DELETE FROM refresh_tokens WHERE token_hash = ?", sha256(token));
    }

    public void revokeAll(long userId) {
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
