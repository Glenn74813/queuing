package ph.gov.philhealth.qms.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.config.QmsProperties;

/**
 * Transactional outbox: records to replicate are written in the same database transaction as the
 * business change, so a committed ticket can never be "forgotten" by synchronization, even if the
 * WAN is down for days. {@link SyncPushJob} delivers them later with retry and backoff.
 */
@Service
public class OutboxService {

    private final OutboxRepository repo;
    private final ObjectMapper mapper;
    private final QmsProperties props;

    public OutboxService(OutboxRepository repo, ObjectMapper mapper, QmsProperties props) {
        this.repo = repo;
        this.mapper = mapper;
        this.props = props;
    }

    public boolean isActive() {
        return props.sync().enabled() && !props.node().isCentral();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, String aggregateType, String aggregateId, Object payload) {
        if (!isActive()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload", e);
        }
        OutboxEntry e = new OutboxEntry();
        e.setId(Ids.newId());
        // Globally unique and stable across retries: the receiving side de-duplicates on it.
        e.setIdempotencyKey(props.node().siteCode() + ":" + e.getId());
        e.setEventType(eventType);
        e.setAggregateType(aggregateType);
        e.setAggregateId(aggregateId);
        e.setSiteCode(props.node().siteCode());
        e.setPayload(json);
        e.setPayloadSha256(Hashing.sha256Hex(json));
        e.setStatus(OutboxEntry.Status.PENDING);
        e.setNextAttemptAt(Instant.now());
        e.setCreatedAt(Instant.now());
        repo.save(e);
    }
}
