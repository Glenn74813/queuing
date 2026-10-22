package ph.gov.philhealth.qms.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.AuthUserLoader;
import ph.gov.philhealth.qms.security.JwtService;
import ph.gov.philhealth.qms.user.UserDtos.AssignmentDto;
import ph.gov.philhealth.qms.user.UserDtos.Me;
import ph.gov.philhealth.qms.user.UserDtos.SiteRef;
import ph.gov.philhealth.qms.user.UserDtos.TokenResponse;

/**
 * Login with account lockout, short-lived JWT access tokens and rotating opaque refresh tokens.
 * Refresh tokens are stored only as SHA-256 hashes and are single-use: each refresh revokes the
 * presented token and issues a new one. Reuse of a revoked token revokes all of the user's tokens.
 */
@Service
public class AuthService {

    /** Window in which re-presenting a just-rotated refresh token is treated as a lost response, not theft. */
    static final Duration REUSE_GRACE = Duration.ofSeconds(60);

    /** Hash checked for unknown usernames so they take as long as wrong passwords (no user enumeration). */
    private final String dummyHash;

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SiteRepository sites;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final TimeService time;
    private final QmsProperties props;
    private final TransactionTemplate tx;

    public AuthService(AppUserRepository users, RefreshTokenRepository refreshTokens, SiteRepository sites,
                       PasswordEncoder encoder, JwtService jwt, AuditService audit, TimeService time,
                       QmsProperties props, TransactionTemplate tx) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.sites = sites;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.time = time;
        this.props = props;
        this.tx = tx;
        this.dummyHash = encoder.encode(Hashing.randomToken(16));
    }

    /** Where a sign-in or refresh came from; shown to administrators in the user's session list. */
    public record ClientInfo(String ipAddress, String userAgent) {
        public static final ClientInfo UNKNOWN = new ClientInfo(null, null);

        String agent() {
            return userAgent == null ? null : userAgent.length() > 200 ? userAgent.substring(0, 200) : userAgent;
        }
    }

    public TokenResponse login(String username, String password) {
        return login(username, password, ClientInfo.UNKNOWN);
    }

    public TokenResponse login(String username, String password, ClientInfo client) {
        // Failure bookkeeping must commit even though the request then fails, hence explicit transactions.
        ApiException failure = tx.execute(status -> {
            Instant now = time.now();
            AppUser u = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
            if (u == null) {
                encoder.matches(password, dummyHash);
                audit.record("LOGIN_FAILED", "AppUser", null, null, "unknown username", null, username);
                return invalidCredentials();
            }
            if (u.isLocked(now)) {
                audit.record("LOGIN_REJECTED_LOCKED", "AppUser", u.getId(), null, null, u.getId(), u.getUsername());
                return new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                        "Account temporarily locked after repeated failed logins. Try again later.");
            }
            if (!encoder.matches(password, u.getPasswordHash())) {
                u.setFailedAttempts(u.getFailedAttempts() + 1);
                if (u.getFailedAttempts() >= props.security().maxFailedLogins()) {
                    u.setLockedUntil(now.plus(Duration.ofMinutes(props.security().lockoutMinutes())));
                    u.setFailedAttempts(0);
                    audit.record("ACCOUNT_LOCKED", "AppUser", u.getId(), null, null, u.getId(), u.getUsername());
                }
                audit.record("LOGIN_FAILED", "AppUser", u.getId(), null, "wrong password", u.getId(), u.getUsername());
                return invalidCredentials();
            }
            if (u.getStatus() != AppUser.Status.ACTIVE) {
                audit.record("LOGIN_REJECTED_STATUS", "AppUser", u.getId(), null, u.getStatus().name(), u.getId(),
                        u.getUsername());
                return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Account is " + u.getStatus());
            }
            return null;
        });
        if (failure != null) {
            throw failure;
        }
        return tx.execute(status -> {
            AppUser u = users.findByUsernameIgnoreCase(username.trim()).orElseThrow();
            u.setFailedAttempts(0);
            u.setLockedUntil(null);
            u.setLastLoginAt(time.now());
            audit.record("LOGIN_SUCCEEDED", "AppUser", u.getId(), null, null, u.getId(), u.getUsername());
            return issueTokens(u, null, client);
        });
    }

    public TokenResponse refresh(String rawToken) {
        return refresh(rawToken, ClientInfo.UNKNOWN);
    }

    public TokenResponse refresh(String rawToken, ClientInfo client) {
        // Explicit transaction: the reuse-detection revocation must commit even though the request fails.
        Object outcome = tx.execute(status -> {
            RefreshToken token = refreshTokens.findByTokenHash(Hashing.sha256Hex(rawToken)).orElse(null);
            Instant now = time.now();
            if (token == null || token.getExpiresAt().isBefore(now)) {
                return unauthorized();
            }
            if (token.getRevokedAt() != null) {
                RefreshToken successor = lostResponseSuccessor(token, now);
                if (successor != null) {
                    // The client never received the rotated pair (reload or dropped connection while the
                    // refresh was in flight). The successor was never used, so replace it with a new pair.
                    successor.setRevokedAt(now);
                    successor.setRevokedReason("SUPERSEDED");
                    AppUser u = users.findById(token.getUserId()).filter(x -> x.getStatus() == AppUser.Status.ACTIVE)
                            .orElse(null);
                    if (u == null) {
                        return unauthorized();
                    }
                    audit.record("REFRESH_TOKEN_GRACE_REUSE", "AppUser", u.getId(), null, null, u.getId(), u.getUsername());
                    return issueTokens(u, token, client);
                }
                // Any other reuse suggests theft: revoke every refresh token of the user.
                refreshTokens.revokeAllForUser(token.getUserId(), now, "REUSE_DETECTED");
                audit.record("REFRESH_TOKEN_REUSE", "AppUser", token.getUserId(), null, null, token.getUserId(), null);
                return unauthorized();
            }
            AppUser u = users.findById(token.getUserId()).filter(x -> x.getStatus() == AppUser.Status.ACTIVE)
                    .orElse(null);
            if (u == null) {
                return unauthorized();
            }
            token.setRevokedAt(now);
            token.setRevokedReason("ROTATED");
            return issueTokens(u, token, client);
        });
        if (outcome instanceof ApiException e) {
            throw e;
        }
        return (TokenResponse) outcome;
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(Hashing.sha256Hex(rawToken)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(time.now());
                t.setRevokedReason("LOGOUT");
                audit.record("LOGOUT", "AppUser", t.getUserId(), null, null, t.getUserId(), null);
            }
        });
    }

    @Transactional
    public void changePassword(AuthUser current, String currentPassword, String newPassword) {
        AppUser u = users.findById(current.id()).orElseThrow();
        if (!encoder.matches(currentPassword, u.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        if (encoder.matches(newPassword, u.getPasswordHash())) {
            throw ApiException.badRequest("New password must differ from the current one");
        }
        PasswordPolicy.validate(newPassword, u.getUsername());
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        u.setPasswordChangedAt(time.now());
        refreshTokens.revokeAllForUser(u.getId(), time.now(), "REVOKED");
        audit.record("PASSWORD_CHANGED", "AppUser", u.getId(), null, null);
    }

    @Transactional(readOnly = true)
    public Me me(UUID userId) {
        return toMe(users.findById(userId).orElseThrow(AuthService::unauthorized));
    }

    /**
     * Grace rule for a revoked token presented again: allowed only if it was revoked by normal rotation
     * less than {@link #REUSE_GRACE} ago and the token that replaced it has never been used itself.
     * Returns that unused successor, or null when the reuse must be treated as theft.
     */
    private RefreshToken lostResponseSuccessor(RefreshToken token, Instant now) {
        if (!"ROTATED".equals(token.getRevokedReason()) || token.getReplacedBy() == null
                || token.getRevokedAt().isBefore(now.minus(REUSE_GRACE))) {
            return null;
        }
        return refreshTokens.findById(token.getReplacedBy()).filter(s -> s.getRevokedAt() == null).orElse(null);
    }

    /** A new pair; with a parent, the pair continues the parent's sign-in session. */
    private TokenResponse issueTokens(AppUser u, RefreshToken parent, ClientInfo client) {
        String raw = "qrt_" + Hashing.randomToken(32);
        RefreshToken rt = new RefreshToken();
        rt.setId(Ids.newId());
        rt.setUserId(u.getId());
        rt.setTokenHash(Hashing.sha256Hex(raw));
        rt.setCreatedAt(time.now());
        rt.setSessionId(parent != null ? parent.getSessionId() : rt.getId());
        rt.setSessionStartedAt(parent != null ? parent.getSessionStartedAt() : rt.getCreatedAt());
        rt.setIpAddress(client.ipAddress() != null ? client.ipAddress() : parent != null ? parent.getIpAddress() : null);
        rt.setUserAgent(client.agent() != null ? client.agent() : parent != null ? parent.getUserAgent() : null);
        rt.setExpiresAt(time.now().plus(Duration.ofHours(props.security().refreshTokenHours())));
        refreshTokens.save(rt);
        if (parent != null) {
            parent.setReplacedBy(rt.getId());
        }
        return new TokenResponse(jwt.issueAccessToken(u.getId(), u.getUsername(), rt.getSessionId()), raw,
                jwt.accessTtlSeconds(),
                toMe(u));
    }

    private Me toMe(AppUser u) {
        AuthUser au = AuthUserLoader.toAuthUser(u);
        List<SiteRef> siteRefs = sites.findAllByOrderByCodeAsc().stream()
                .filter(s -> s.isActive() && au.grants().stream().anyMatch(g -> g.covers(s.getId(), s.getRegionId())))
                .sorted(Comparator.comparing(s -> s.getName()))
                .map(s -> new SiteRef(s.getId(), s.getCode(), s.getName())).toList();
        return new Me(u.getId(), u.getUsername(), u.getFullName(), u.isMustChangePassword(), au.permissions(),
                u.getAssignments().stream().map(AssignmentDto::of).toList(), siteRefs,
                Math.max(0, props.security().idleTimeoutMinutes()));
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid username or password");
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Session expired. Please sign in again.");
    }
}
