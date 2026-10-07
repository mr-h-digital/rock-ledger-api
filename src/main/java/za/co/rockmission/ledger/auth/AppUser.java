package za.co.rockmission.ledger.auth;

import java.time.Instant;

public record AppUser(long id, String email, String fullName, String role, String passwordHash,
                      boolean mustChangePassword, String totpSecretEnc, boolean totpEnabled,
                      Long lastTotpStep, boolean active, int failedAttempts, Instant lockedUntil,
                      Instant lastLoginAt) {}
