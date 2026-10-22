package ph.gov.philhealth.qms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.config.QmsProperties;

/**
 * Issues and verifies short-lived HS256 access tokens. The token only identifies the user;
 * permissions and scope are re-read from the database on each request (see {@link AuthUserLoader}).
 */
@Service
public class JwtService {

    private static final String ISSUER = "philhealth-qms";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(QmsProperties props) {
        String secret = props.security().jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("QMS_JWT_SECRET must be set (base64, at least 256 bits)");
        }
        byte[] bytes = Decoders.BASE64.decode(secret);
        if (bytes.length < 32) {
            throw new IllegalStateException("QMS_JWT_SECRET must decode to at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = Duration.ofMinutes(props.security().accessTokenMinutes());
    }

    /** An authentic token: its user and the sign-in session it belongs to (null for pre-V11 tokens). */
    public record Verified(UUID userId, UUID sessionId) {
    }

    public String issueAccessToken(UUID userId, String username, UUID sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(Ids.newId().toString())
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("username", username)
                .claim("sid", sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    /** Returns the user and session when the token is authentic, unexpired and issued by this system. */
    public Optional<Verified> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build()
                    .parseSignedClaims(token).getPayload();
            String sid = claims.get("sid", String.class);
            return Optional.of(new Verified(UUID.fromString(claims.getSubject()), sid != null ? UUID.fromString(sid) : null));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
