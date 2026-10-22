package ph.gov.philhealth.qms.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
public class RefreshToken {
    @Id
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    /** Why the token stopped being valid: ROTATED, SUPERSEDED, LOGOUT, REVOKED or REUSE_DETECTED. */
    private String revokedReason;
    /** The token issued when this one was rotated. */
    private UUID replacedBy;
    private Instant createdAt;
    /** Id of the login that started this rotation chain; shared by every token of the session. */
    private UUID sessionId;
    private Instant sessionStartedAt;
    /** Client of the latest sign-in or refresh. */
    private String ipAddress;
    private String userAgent;
}
