package ph.gov.philhealth.qms.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.sync.OutboxService;

/**
 * Records sensitive administrative and operational actions. Runs inside the caller's transaction,
 * so an audit row exists if and only if the audited change was committed.
 */
@Service
public class AuditService {

    private final AuditLogRepository repo;
    private final TimeService time;
    private final OutboxService outbox;

    public AuditService(AuditLogRepository repo, TimeService time, OutboxService outbox) {
        this.repo = repo;
        this.time = time;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, UUID siteId, String details) {
        AuthUser user = CurrentUser.find().orElse(null);
        record(action, entityType, entityId, siteId, details, user != null ? user.id() : null,
                user != null ? user.username() : null);
    }

    /** Variant for events without an authenticated user, e.g. failed logins. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, UUID siteId, String details,
                       UUID actorId, String actorUsername) {
        AuditLog log = new AuditLog();
        log.setId(Ids.newId());
        log.setAt(time.now());
        log.setActorUserId(actorId);
        log.setActorUsername(actorUsername);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId != null ? entityId.toString() : null);
        log.setSiteId(siteId);
        log.setDetails(details != null && details.length() > 4000 ? details.substring(0, 4000) : details);
        log.setIpAddress(clientIp());
        repo.save(log);
        outbox.enqueue("AUDIT_RECORDED", "AuditLog", log.getId().toString(), log);
    }

    public static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getRemoteAddr();   // X-Forwarded-For is resolved by forward-headers-strategy
        }
        return null;
    }
}
