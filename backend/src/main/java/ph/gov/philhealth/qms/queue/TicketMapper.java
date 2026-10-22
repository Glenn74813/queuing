package ph.gov.philhealth.qms.queue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.catalog.PriorityCategory;
import ph.gov.philhealth.qms.catalog.PriorityCategoryRepository;
import ph.gov.philhealth.qms.catalog.SiteService;
import ph.gov.philhealth.qms.catalog.SiteServiceRepository;
import ph.gov.philhealth.qms.counter.ServiceCounter;
import ph.gov.philhealth.qms.counter.ServiceCounterRepository;
import ph.gov.philhealth.qms.queue.QueueDtos.TicketDto;

/** Builds ticket DTOs with display names resolved in bulk (one query per lookup type). */
@Component
public class TicketMapper {

    private final SiteServiceRepository siteServices;
    private final PriorityCategoryRepository priorities;
    private final ServiceCounterRepository counters;

    public TicketMapper(SiteServiceRepository siteServices, PriorityCategoryRepository priorities,
                        ServiceCounterRepository counters) {
        this.siteServices = siteServices;
        this.priorities = priorities;
        this.counters = counters;
    }

    /** Name lookups for one site. */
    public record Lookup(Map<UUID, SiteService> services, Map<UUID, PriorityCategory> priorities,
                         Map<UUID, ServiceCounter> counters) {

        public String serviceName(UUID siteServiceId) {
            SiteService s = services.get(siteServiceId);
            return s != null ? s.getServiceItem().getName() : null;
        }

        public String counterName(UUID counterId) {
            ServiceCounter c = counterId != null ? counters.get(counterId) : null;
            return c != null ? c.getName() : null;
        }
    }

    @Transactional(readOnly = true)
    public Lookup lookup(UUID siteId) {
        return new Lookup(
                index(siteServices.findBySiteIdOrderBySortOrderAsc(siteId), SiteService::getId),
                index(priorities.findAll(), PriorityCategory::getId),
                index(counters.findBySiteIdOrderByCodeAsc(siteId), ServiceCounter::getId));
    }

    public TicketDto toDto(Ticket t, Lookup l) {
        PriorityCategory p = t.getPriorityCategoryId() != null ? l.priorities().get(t.getPriorityCategoryId()) : null;
        SiteService current = l.services().get(t.getSiteServiceId());
        UUID next = current != null ? current.getNextSiteServiceId() : null;
        return new TicketDto(t.getId(), t.getDisplayNumber(), t.getBusinessDate(), t.getSiteId(),
                t.getSiteServiceId(), l.serviceName(t.getSiteServiceId()), p != null ? p.getCode() : null,
                p != null ? p.getName() : null, t.getChannel(), t.getStatus(), t.getStageNo(), t.getCounterId(),
                l.counterName(t.getCounterId()), t.getAgentName(), t.getIssuedAt(), t.getFirstCalledAt(),
                t.getLastCalledAt(), t.getCompletedAt(), t.getRecallCount(), next, l.serviceName(next));
    }

    public List<TicketDto> toDtos(Collection<Ticket> tickets, Lookup l) {
        return tickets.stream().map(t -> toDto(t, l)).toList();
    }

    @Transactional(readOnly = true)
    public TicketDto toDto(Ticket t) {
        return toDto(t, lookup(t.getSiteId()));
    }

    private static <T> Map<UUID, T> index(Collection<T> items, Function<T, UUID> key) {
        return items.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
