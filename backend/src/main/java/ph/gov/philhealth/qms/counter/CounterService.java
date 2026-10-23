package ph.gov.philhealth.qms.counter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.catalog.SiteServiceRepository;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.counter.CounterDtos.AgentRef;
import ph.gov.philhealth.qms.counter.CounterDtos.CounterDto;
import ph.gov.philhealth.qms.counter.CounterDtos.CounterInput;
import ph.gov.philhealth.qms.counter.CounterDtos.HoursDto;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;
import ph.gov.philhealth.qms.sync.OutboxService;
import ph.gov.philhealth.qms.sync.Snapshots;
import ph.gov.philhealth.qms.user.AppUser;
import ph.gov.philhealth.qms.user.AppUserRepository;

@Service
public class CounterService {

    private final ServiceCounterRepository counters;
    private final SiteServiceRepository siteServices;
    private final AgentSessionRepository sessions;
    private final AppUserRepository users;
    private final ScopeService scope;
    private final AuditService audit;
    private final OutboxService outbox;
    private final SiteRepository sites;
    private final TimeService time;

    public CounterService(ServiceCounterRepository counters, SiteServiceRepository siteServices,
                          AgentSessionRepository sessions, AppUserRepository users, ScopeService scope,
                          AuditService audit, OutboxService outbox, SiteRepository sites, TimeService time) {
        this.sites = sites;
        this.time = time;
        this.counters = counters;
        this.siteServices = siteServices;
        this.sessions = sessions;
        this.users = users;
        this.scope = scope;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<CounterDto> list(UUID siteId) {
        scope.requireSite(Permission.QUEUE_VIEW, siteId);
        return listUnchecked(siteId);
    }

    /** Counter list with live agent status; callers must have checked scope. */
    @Transactional(readOnly = true)
    public List<CounterDto> listUnchecked(UUID siteId) {
        Map<UUID, AgentSession> open = sessions.findOpenBySite(siteId).stream()
                .collect(Collectors.toMap(AgentSession::getCounterId, Function.identity()));
        Map<UUID, AppUser> agents = users.findAllById(open.values().stream().map(AgentSession::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        String zone = sites.findById(siteId).map(Site::getTimezone).orElse("Asia/Manila");
        LocalDate today = time.businessDate(zone);
        LocalTime now = time.localTime(zone);
        return counters.findBySiteIdOrderByCodeAsc(siteId).stream().map(c -> {
            AgentSession s = open.get(c.getId());
            AppUser agent = s != null ? agents.get(s.getUserId()) : null;
            List<HoursDto> hours = c.getHours().stream()
                    .sorted(Comparator.comparingInt(CounterHours::getDayOfWeek).thenComparing(CounterHours::getOpenTime))
                    .map(h -> new HoursDto(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime())).toList();
            return new CounterDto(c.getId(), c.getSiteId(), c.getCode(), c.getName(), c.isActive(),
                    c.getSiteServiceIds(), agent != null ? new AgentRef(agent.getId(), agent.getFullName()) : null,
                    s != null ? s.getStatus() : null, hours, c.scheduleState(today, now));
        }).toList();
    }

    /**
     * True when some active counter eligible for the service can serve now: it is staffed, open by its
     * schedule, or has no schedule. Kiosks do not ticket clients for a service nobody will serve.
     */
    public static boolean servable(UUID siteServiceId, List<CounterDto> counterList) {
        return counterList.stream().anyMatch(c -> c.active() && c.siteServiceIds().contains(siteServiceId)
                && (c.currentAgent() != null || c.scheduleState() != ServiceCounter.ScheduleState.CLOSED));
    }

    @Transactional
    public CounterDto create(UUID siteId, CounterInput in) {
        scope.requireSite(Permission.COUNTER_MANAGE, siteId);
        ServiceCounter c = new ServiceCounter();
        c.setSiteId(siteId);
        apply(c, in);
        counters.saveAndFlush(c);
        audit.record("COUNTER_CREATED", "ServiceCounter", c.getId(), siteId, c.getCode() + " " + c.getSiteServiceIds());
        outbox.enqueue("COUNTER_UPSERT", "ServiceCounter", c.getId().toString(), Snapshots.of(c));
        return listUnchecked(siteId).stream().filter(x -> x.id().equals(c.getId())).findFirst().orElseThrow();
    }

    @Transactional
    public CounterDto update(UUID siteId, UUID id, CounterInput in) {
        scope.requireSite(Permission.COUNTER_MANAGE, siteId);
        ServiceCounter c = counters.findById(id).filter(x -> x.getSiteId().equals(siteId))
                .orElseThrow(() -> ApiException.notFound("Counter"));
        apply(c, in);
        counters.flush();
        audit.record("COUNTER_UPDATED", "ServiceCounter", c.getId(), siteId,
                "active=%s services=%s hours=%s".formatted(c.isActive(), c.getSiteServiceIds(), c.getHours().size()));
        outbox.enqueue("COUNTER_UPSERT", "ServiceCounter", c.getId().toString(), Snapshots.of(c));
        return listUnchecked(siteId).stream().filter(x -> x.id().equals(c.getId())).findFirst().orElseThrow();
    }

    private void apply(ServiceCounter c, CounterInput in) {
        var valid = siteServices.findAllById(in.siteServiceIds());
        if (valid.size() != in.siteServiceIds().size()
                || valid.stream().anyMatch(s -> !s.getSiteId().equals(c.getSiteId()))) {
            throw ApiException.badRequest("Every eligible service must belong to the same site");
        }
        c.setCode(in.code());
        c.setName(in.name());
        c.setActive(in.active());
        c.setSiteServiceIds(new HashSet<>(in.siteServiceIds()));
        if (in.hours() != null) {
            c.setHours(validHours(in.hours()));
        }
    }

    /** Windows must have closing after opening and must not overlap on the same day. */
    static Set<CounterHours> validHours(List<HoursDto> input) {
        List<HoursDto> sorted = input.stream()
                .sorted(Comparator.comparingInt(HoursDto::dayOfWeek).thenComparing(HoursDto::openTime)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            HoursDto h = sorted.get(i);
            if (!h.closeTime().isAfter(h.openTime())) {
                throw ApiException.badRequest("Closing time must be after opening time");
            }
            if (i > 0 && sorted.get(i - 1).dayOfWeek() == h.dayOfWeek()
                    && sorted.get(i - 1).closeTime().isAfter(h.openTime())) {
                throw ApiException.badRequest("Opening hours on the same day must not overlap");
            }
        }
        Set<CounterHours> result = new HashSet<>();
        sorted.forEach(h -> result.add(new CounterHours(h.dayOfWeek(), h.openTime(), h.closeTime())));
        return result;
    }
}
