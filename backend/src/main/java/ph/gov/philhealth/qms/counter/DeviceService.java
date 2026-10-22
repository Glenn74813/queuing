package ph.gov.philhealth.qms.counter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceDto;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceInput;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceUpdate;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceWithKey;
import ph.gov.philhealth.qms.security.DevicePrincipal;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;
import ph.gov.philhealth.qms.sync.OutboxService;
import ph.gov.philhealth.qms.sync.Snapshots;

@Service
public class DeviceService {

    private static final Duration LAST_SEEN_GRANULARITY = Duration.ofMinutes(1);

    private final DeviceRepository repo;
    private final ScopeService scope;
    private final AuditService audit;
    private final OutboxService outbox;

    public DeviceService(DeviceRepository repo, ScopeService scope, AuditService audit, OutboxService outbox) {
        this.repo = repo;
        this.scope = scope;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Resolves an active device by key hash, refreshing its heartbeat at most once a minute. */
    @Transactional
    public Optional<DevicePrincipal> authenticate(String keyHash) {
        return repo.findByApiKeyHash(keyHash).filter(Device::isActive).map(d -> {
            Instant now = Instant.now();
            if (d.getLastSeenAt() == null || d.getLastSeenAt().isBefore(now.minus(LAST_SEEN_GRANULARITY))) {
                d.setLastSeenAt(now);
            }
            return new DevicePrincipal(d.getId(), d.getSiteId(), d.getCode(), d.getType().name());
        });
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> list(UUID siteId) {
        scope.requireSite(Permission.DEVICE_MANAGE, siteId);
        return repo.findBySiteIdOrderByCodeAsc(siteId).stream().map(DeviceDto::of).toList();
    }

    @Transactional
    public DeviceWithKey create(UUID siteId, DeviceInput in) {
        scope.requireSite(Permission.DEVICE_MANAGE, siteId);
        Device d = new Device();
        d.setSiteId(siteId);
        d.setCode(in.code());
        d.setName(in.name());
        d.setType(in.type());
        String key = newKey();
        d.setApiKeyHash(Hashing.sha256Hex(key));
        repo.saveAndFlush(d);
        audit.record("DEVICE_CREATED", "Device", d.getId(), siteId, d.getType() + " " + d.getCode());
        outbox.enqueue("DEVICE_UPSERT", "Device", d.getId().toString(), Snapshots.of(d));
        return new DeviceWithKey(DeviceDto.of(d), key);
    }

    @Transactional
    public DeviceDto update(UUID siteId, UUID id, DeviceUpdate in) {
        scope.requireSite(Permission.DEVICE_MANAGE, siteId);
        Device d = find(siteId, id);
        d.setName(in.name());
        d.setActive(in.active());
        audit.record("DEVICE_UPDATED", "Device", d.getId(), siteId, "active=" + in.active());
        outbox.enqueue("DEVICE_UPSERT", "Device", d.getId().toString(), Snapshots.of(d));
        return DeviceDto.of(d);
    }

    @Transactional
    public DeviceWithKey rotateKey(UUID siteId, UUID id) {
        scope.requireSite(Permission.DEVICE_MANAGE, siteId);
        Device d = find(siteId, id);
        String key = newKey();
        d.setApiKeyHash(Hashing.sha256Hex(key));
        audit.record("DEVICE_KEY_ROTATED", "Device", d.getId(), siteId, d.getCode());
        return new DeviceWithKey(DeviceDto.of(d), key);
    }

    private Device find(UUID siteId, UUID id) {
        return repo.findById(id).filter(d -> d.getSiteId().equals(siteId))
                .orElseThrow(() -> ApiException.notFound("Device"));
    }

    private static String newKey() {
        return "qdk_" + Hashing.randomToken(32);
    }
}
