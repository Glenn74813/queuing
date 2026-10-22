package ph.gov.philhealth.qms.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qms")
public record QmsProperties(Node node, Security security, Storage storage, Sync sync, Queue queue, Kiosk kiosk,
                            Retention retention) {

    public enum NodeRole { SITE, CENTRAL }

    public record Node(NodeRole role, String siteCode, String defaultTimezone) {
        public boolean isCentral() {
            return role == NodeRole.CENTRAL;
        }
    }

    public record Security(String jwtSecret, int accessTokenMinutes, int refreshTokenHours, String corsAllowedOrigins,
                           int maxFailedLogins, int lockoutMinutes, int loginRatePerMinute,
                           String bootstrapAdminPassword, String metricsToken, int idleTimeoutMinutes) {
        public List<String> corsOrigins() {
            if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
                return List.of();
            }
            return Arrays.stream(corsAllowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    public record Storage(String mediaDir, String reportsDir, int reportsRetentionDays) {
    }

    public record Sync(boolean enabled, String centralUrl, String siteKey, long pushIntervalMs,
                       long configPullIntervalMs, int batchSize, int maxBackoffSeconds) {
    }

    public record Queue(String expireCron) {
    }

    /** ticketLinkBaseUrl: origin clients' phones can reach for QR e-ticket links; blank = the kiosk's origin. */
    public record Kiosk(String ticketLinkBaseUrl) {
    }

    /**
     * Days to keep data; 0 keeps it forever. Tickets (with their events and feedback) and the audit log
     * default to forever until PhilHealth sets a retention policy (OI-13).
     */
    public record Retention(String cron, int ticketDays, int auditDays, int sentOutboxDays, int expiredTokenDays,
                            int batchSize) {
    }
}
