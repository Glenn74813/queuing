package ph.gov.philhealth.qms.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.security.SiteKeyAuthenticationFilter;

/**
 * Site node → central node delivery of the transactional outbox. Runs independently of queue
 * processing: if central is unreachable, local operations continue and entries are retried with
 * exponential backoff (5 s, 10 s, 20 s ... capped at {@code max-backoff-seconds}).
 */
@Component
public class SyncPushJob {

    private static final Logger log = LoggerFactory.getLogger(SyncPushJob.class);

    private final OutboxRepository outbox;
    private final SyncStateService state;
    private final QmsProperties props;
    private final TransactionTemplate tx;
    private final RestClient client;
    private final ph.gov.philhealth.qms.monitoring.QmsMetrics metrics;

    public SyncPushJob(OutboxRepository outbox, SyncStateService state, QmsProperties props, TransactionTemplate tx,
                       ph.gov.philhealth.qms.monitoring.QmsMetrics metrics) {
        this.metrics = metrics;
        this.outbox = outbox;
        this.state = state;
        this.props = props;
        this.tx = tx;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${qms.sync.push-interval-ms}", initialDelay = 10_000)
    public void run() {
        if (!props.sync().enabled() || props.node().isCentral() || blank(props.sync().centralUrl())) {
            return;
        }
        List<OutboxEntry> due = outbox.findDue(Instant.now(), PageRequest.of(0, props.sync().batchSize()));
        if (due.isEmpty()) {
            return;
        }
        SyncEnvelope.Batch batch = new SyncEnvelope.Batch(due.stream().map(e -> new SyncEnvelope.Entry(
                e.getIdempotencyKey(), e.getSiteCode(), e.getEventType(), e.getAggregateType(), e.getAggregateId(),
                e.getPayload(), e.getPayloadSha256(), e.getCreatedAt())).toList());
        try {
            SyncEnvelope.Result result = client.post()
                    .uri(props.sync().centralUrl() + "/api/v1/sync/ingest")
                    .header(SiteKeyAuthenticationFilter.HEADER, props.sync().siteKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batch)
                    .retrieve()
                    .body(SyncEnvelope.Result.class);
            applyResult(due, result);
        } catch (RuntimeException e) {
            log.warn("Sync push of {} entries failed: {}", due.size(), e.getMessage());
            metrics.syncPush("transport_error", due.size());
            tx.executeWithoutResult(s -> due.forEach(entry -> markFailed(entry.getId(), "Transport: " + e.getMessage())));
        }
    }

    private void applyResult(List<OutboxEntry> due, SyncEnvelope.Result result) {
        Set<String> ok = new HashSet<>(result.accepted());
        ok.addAll(result.duplicates());
        Map<String, String> rejected = result.rejected().stream()
                .collect(Collectors.toMap(SyncEnvelope.Rejected::key, SyncEnvelope.Rejected::reason, (a, b) -> a));
        Map<String, OutboxEntry> byKey = due.stream()
                .collect(Collectors.toMap(OutboxEntry::getIdempotencyKey, Function.identity()));
        tx.executeWithoutResult(s -> byKey.forEach((key, entry) -> {
            if (ok.contains(key)) {
                outbox.findById(entry.getId()).ifPresent(e -> {
                    e.setStatus(OutboxEntry.Status.SENT);
                    e.setSentAt(Instant.now());
                    e.setLastError(null);
                });
            } else {
                markFailed(entry.getId(), rejected.getOrDefault(key, "No acknowledgement"));
            }
        }));
        if (!ok.isEmpty()) {
            state.put(SyncStateService.LAST_PUSH, Instant.now().toString());
        }
        metrics.syncPush("accepted", ok.size());
        metrics.syncPush("rejected", byKey.size() - (int) byKey.keySet().stream().filter(ok::contains).count());
    }

    private void markFailed(java.util.UUID id, String error) {
        outbox.findById(id).ifPresent(e -> {
            e.setAttempts(e.getAttempts() + 1);
            e.setStatus(OutboxEntry.Status.FAILED);
            e.setLastError(error.length() > 1000 ? error.substring(0, 1000) : error);
            e.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(e.getAttempts(),
                    props.sync().maxBackoffSeconds())));
        });
    }

    static long backoffSeconds(int attempts, int max) {
        long secs = 5L << Math.min(attempts - 1, 20);
        return Math.min(secs, max);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
