package ph.gov.philhealth.qms.queue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.catalog.PriorityCategory;
import ph.gov.philhealth.qms.catalog.PriorityCategoryRepository;
import ph.gov.philhealth.qms.catalog.SiteService;
import ph.gov.philhealth.qms.cms.ContentService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.counter.AgentSession;
import ph.gov.philhealth.qms.counter.CounterDtos.CounterDto;
import ph.gov.philhealth.qms.counter.CounterService;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.queue.QueueDtos.CallEntry;
import ph.gov.philhealth.qms.queue.QueueDtos.DisplayBoard;
import ph.gov.philhealth.qms.queue.QueueDtos.IssuedTicket;
import ph.gov.philhealth.qms.queue.QueueDtos.KioskConfig;
import ph.gov.philhealth.qms.queue.QueueDtos.KioskPriority;
import ph.gov.philhealth.qms.queue.QueueDtos.KioskService;
import ph.gov.philhealth.qms.queue.QueueDtos.QueueSnapshot;
import ph.gov.philhealth.qms.queue.QueueDtos.QueueStats;
import ph.gov.philhealth.qms.queue.QueueDtos.ServiceLoad;
import ph.gov.philhealth.qms.queue.QueueDtos.SiteRef;
import ph.gov.philhealth.qms.queue.QueueDtos.TicketDetail;
import ph.gov.philhealth.qms.queue.QueueDtos.TicketEventDto;
import ph.gov.philhealth.qms.security.DevicePrincipal;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

/** Read models for supervisors, kiosks and displays. */
@Service
public class QueueQueryService {

    private final TicketRepository tickets;
    private final TicketEventRepository events;
    private final TicketMapper mapper;
    private final SiteRepository sites;
    private final PriorityCategoryRepository priorities;
    private final CounterService counters;
    private final ContentService content;
    private final ScopeService scope;
    private final TimeService time;
    private final JdbcTemplate jdbc;
    private final ph.gov.philhealth.qms.config.QmsProperties props;

    public QueueQueryService(TicketRepository tickets, TicketEventRepository events, TicketMapper mapper,
                             SiteRepository sites, PriorityCategoryRepository priorities, CounterService counters,
                             ContentService content, ScopeService scope, TimeService time, JdbcTemplate jdbc,
                             ph.gov.philhealth.qms.config.QmsProperties props) {
        this.tickets = tickets;
        this.events = events;
        this.mapper = mapper;
        this.sites = sites;
        this.priorities = priorities;
        this.counters = counters;
        this.content = content;
        this.scope = scope;
        this.time = time;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public QueueSnapshot snapshot(UUID siteId) {
        Site site = scope.requireSite(Permission.QUEUE_VIEW, siteId);
        LocalDate today = time.businessDate(site.getTimezone());
        TicketMapper.Lookup lookup = mapper.lookup(siteId);
        List<Ticket> open = tickets.findBySiteDateAndStatuses(siteId, today,
                EnumSet.of(TicketStatus.WAITING, TicketStatus.CALLED, TicketStatus.ON_HOLD));
        List<CounterDto> counterList = counters.listUnchecked(siteId);
        return new QueueSnapshot(siteId, today,
                mapper.toDtos(filter(open, TicketStatus.WAITING), lookup),
                mapper.toDtos(filter(open, TicketStatus.CALLED), lookup),
                mapper.toDtos(filter(open, TicketStatus.ON_HOLD), lookup),
                counterList, serviceLoads(lookup, open, counterList, null), stats(siteId, today));
    }

    /**
     * Clients per service at a site over the last {@code days} business days (today included): every
     * service offered there, also those not availed yet, counted by the service the client chose at the
     * kiosk. Voided tickets (issued in error) are excluded.
     */
    @Transactional(readOnly = true)
    public QueueDtos.ServiceVolumeReport serviceVolume(UUID siteId, int days) {
        Site site = scope.requireSite(Permission.QUEUE_VIEW, siteId);
        int span = Math.max(1, Math.min(days, 90));
        LocalDate to = time.businessDate(site.getTimezone());
        LocalDate from = to.minusDays(span - 1L);
        List<QueueDtos.ServiceVolume> rows = jdbc.query("""
                select ss.id, si.code, si.name,
                       count(t.id) as issued,
                       count(t.id) filter (where t.status = 'COMPLETED') as served,
                       count(t.id) filter (where t.status in ('REMOVED', 'EXPIRED')) as abandoned,
                       count(t.id) filter (where t.status in ('WAITING', 'CALLED', 'ON_HOLD')) as waiting
                from site_service ss
                join service_item si on si.id = ss.service_item_id
                left join ticket t on t.original_site_service_id = ss.id and t.business_date between ? and ?
                                  and t.status <> 'VOIDED'
                where ss.site_id = ?
                group by ss.id, si.code, si.name, ss.active, ss.sort_order
                having ss.active or count(t.id) > 0
                order by count(t.id) desc, ss.sort_order, si.name
                """, (rs, i) -> new QueueDtos.ServiceVolume(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)), from, to, siteId);
        return new QueueDtos.ServiceVolumeReport(siteId, from, to, rows);
    }

    /**
     * Overview across every site the user may view with QUEUE_VIEW (all active sites for national
     * roles): today's figures per site and in total, and clients per catalog service over the last
     * {@code days} days. "Today" is each site's own business date.
     */
    @Transactional(readOnly = true)
    public QueueDtos.Overview overview(int days) {
        java.util.Set<UUID> allowed = scope.accessibleSiteIds(Permission.QUEUE_VIEW);
        boolean national = allowed == null;
        if (!national && allowed.isEmpty()) {
            throw ApiException.forbidden("No sites in your scope");
        }
        String siteFilter = national ? "" : " and s.id = any(?)";
        Object[] siteArgs = national ? new Object[0] : new Object[] {allowed.toArray(UUID[]::new)};
        List<QueueDtos.SiteOverview> sites = jdbc.query("""
                with today as (
                  select s.id as site_id, (now() at time zone s.timezone)::date as d from site s where s.active
                ),
                tk as (
                  select t.site_id,
                         count(*) filter (where t.status <> 'VOIDED') as issued,
                         count(*) filter (where t.status = 'COMPLETED') as completed,
                         count(*) filter (where t.status = 'WAITING') as waiting,
                         avg(extract(epoch from (t.first_called_at - t.issued_at)) / 60.0)
                             filter (where t.first_called_at is not null) as avg_wait
                  from ticket t join today on today.site_id = t.site_id and t.business_date = today.d
                  group by t.site_id
                ),
                ctr as (
                  select c.site_id, count(*) filter (where c.active) as total,
                         count(distinct a.counter_id) as staffed
                  from service_counter c
                  left join agent_session a on a.counter_id = c.id and a.status <> 'ENDED'
                  group by c.site_id
                )
                select s.id, s.code, s.name, r.name, coalesce(tk.issued, 0), coalesce(tk.completed, 0),
                       coalesce(tk.waiting, 0), tk.avg_wait, coalesce(ctr.staffed, 0), coalesce(ctr.total, 0)
                from site s
                join region r on r.id = s.region_id
                left join tk on tk.site_id = s.id
                left join ctr on ctr.site_id = s.id
                where s.active""" + siteFilter + " order by r.name, s.name",
                (rs, i) -> new QueueDtos.SiteOverview(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                        rs.getObject(8) == null ? null : Math.round(rs.getDouble(8) * 10) / 10.0, rs.getLong(9),
                        rs.getLong(10)), siteArgs);

        long issued = sites.stream().mapToLong(QueueDtos.SiteOverview::issuedToday).sum();
        long completed = sites.stream().mapToLong(QueueDtos.SiteOverview::completedToday).sum();
        long waiting = sites.stream().mapToLong(QueueDtos.SiteOverview::waitingNow).sum();
        // Mean over all of today's called tickets in scope (not an average of the site averages).
        Double avgWait = jdbc.query("""
                select avg(extract(epoch from (t.first_called_at - t.issued_at)) / 60.0)
                from ticket t join site s on s.id = t.site_id
                where s.active and t.business_date = (now() at time zone s.timezone)::date
                  and t.first_called_at is not null""" + siteFilter,
                rs -> rs.next() && rs.getObject(1) != null ? Math.round(rs.getDouble(1) * 10) / 10.0 : null, siteArgs);

        int span = Math.max(1, Math.min(days, 90));
        LocalDate to = time.businessDate(props.node().defaultTimezone());
        LocalDate from = to.minusDays(span - 1L);
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (!national) {
            args.add(allowed.toArray(UUID[]::new));
        }
        List<QueueDtos.CatalogServiceVolume> services = jdbc.query("""
                select si.id, si.code, si.name,
                       count(t.id) as issued,
                       count(t.id) filter (where t.status = 'COMPLETED') as served,
                       count(t.id) filter (where t.status in ('REMOVED', 'EXPIRED')) as abandoned,
                       count(t.id) filter (where t.status in ('WAITING', 'CALLED', 'ON_HOLD')) as waiting,
                       count(distinct ss.site_id) filter (where ss.active) as sites_offering
                from service_item si
                join site_service ss on ss.service_item_id = si.id
                join site s on s.id = ss.site_id and s.active
                left join ticket t on t.original_site_service_id = ss.id and t.business_date between ? and ?
                                  and t.status <> 'VOIDED'
                where true""" + siteFilter + """

                group by si.id, si.code, si.name, si.sort_order
                having bool_or(ss.active) or count(t.id) > 0
                order by count(t.id) desc, si.sort_order
                """, (rs, i) -> new QueueDtos.CatalogServiceVolume(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)),
                args.toArray());
        return new QueueDtos.Overview(national, sites.size(), new QueueStats(issued, completed, waiting, avgWait),
                sites, from, to, services);
    }

    @Transactional(readOnly = true)
    public TicketDetail detail(UUID ticketId) {
        Ticket t = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        scope.requireSite(Permission.QUEUE_VIEW, t.getSiteId());
        TicketMapper.Lookup lookup = mapper.lookup(t.getSiteId());
        List<TicketEventDto> history = events.findByTicketIdOrderByAtAsc(ticketId).stream()
                .map(e -> new TicketEventDto(e.getId(), e.getEventType(), e.getFromStatus(), e.getToStatus(),
                        e.getAt(), e.getActorName(), lookup.counterName(e.getCounterId()), e.getReason(),
                        lookup.serviceName(e.getSiteServiceId())))
                .toList();
        return new TicketDetail(mapper.toDto(t, lookup), history);
    }

    @Transactional(readOnly = true)
    public KioskConfig kioskConfig(DevicePrincipal device) {
        Site site = sites.findById(device.siteId()).orElseThrow();
        LocalDate today = time.businessDate(site.getTimezone());
        LocalTime now = time.localTime(site.getTimezone());
        TicketMapper.Lookup lookup = mapper.lookup(site.getId());
        List<Ticket> waiting = tickets.findBySiteDateAndStatuses(site.getId(), today, EnumSet.of(TicketStatus.WAITING));
        List<CounterDto> counterList = counters.listUnchecked(site.getId());
        Map<UUID, ServiceLoad> loads = serviceLoads(lookup, waiting, counterList, null).stream()
                .collect(Collectors.toMap(ServiceLoad::siteServiceId, l -> l));
        List<KioskService> services = lookup.services().values().stream()
                .filter(s -> s.isActive() && s.getServiceItem().isActive())
                .sorted(Comparator.comparingInt(SiteService::getSortOrder))
                .map(s -> {
                    ServiceLoad load = loads.get(s.getId());
                    return new KioskService(s.getId(), s.getServiceItem().getName(),
                            s.getServiceItem().getDescription(), s.getPrefix(), s.isOpenAt(today, now),
                            load != null ? load.waitingCount() : 0, load != null ? load.estimatedWaitMinutes() : 0);
                }).toList();
        List<KioskPriority> prios = priorities.findAllByOrderByRankAscCodeAsc().stream()
                .filter(PriorityCategory::isActive)
                .map(p -> new KioskPriority(p.getId(), p.getCode(), p.getName(), p.getDescription())).toList();
        return new KioskConfig(ref(site), services, prios, content.activeFor(site));
    }

    @Transactional(readOnly = true)
    public IssuedTicket issued(Ticket t) {
        Site site = sites.findById(t.getSiteId()).orElseThrow();
        TicketMapper.Lookup lookup = mapper.lookup(site.getId());
        long ahead = tickets.countAhead(site.getId(), t.getBusinessDate(), t.getSiteServiceId(), t.getPriorityRank(),
                t.getQueuedAt());
        SiteService service = lookup.services().get(t.getSiteServiceId());
        int servers = (int) Math.max(1, activeServers(counters.listUnchecked(site.getId()), t.getSiteServiceId()));
        int est = (int) Math.ceil((double) ahead * service.getEstimatedDurationMinutes() / servers);
        return new IssuedTicket(mapper.toDto(t, lookup), site.getName(), ahead, est);
    }

    @Transactional(readOnly = true)
    public DisplayBoard board(DevicePrincipal device) {
        Site site = sites.findById(device.siteId()).orElseThrow();
        LocalDate today = time.businessDate(site.getTimezone());
        TicketMapper.Lookup lookup = mapper.lookup(site.getId());
        List<CallEntry> nowServing = tickets.findBySiteDateAndStatuses(site.getId(), today,
                        EnumSet.of(TicketStatus.CALLED)).stream()
                .sorted(Comparator.comparing(Ticket::getLastCalledAt).reversed())
                .map(t -> call(t, lookup)).toList();
        List<CallEntry> recent = tickets.findRecentlyCalled(site.getId(), today).stream()
                .filter(t -> t.getStatus() != TicketStatus.CALLED).map(t -> call(t, lookup)).toList();
        long waiting = tickets.findBySiteDateAndStatuses(site.getId(), today, EnumSet.of(TicketStatus.WAITING)).size();
        return new DisplayBoard(ref(site), nowServing, recent, waiting, content.activeFor(site), time.now());
    }

    private static CallEntry call(Ticket t, TicketMapper.Lookup lookup) {
        return new CallEntry(t.getId(), t.getDisplayNumber(), lookup.counterName(t.getCounterId()),
                lookup.serviceName(t.getSiteServiceId()), t.getLastCalledAt(), t.getRecallCount());
    }

    /**
     * Estimated wait per service = waiting tickets x configured duration / available counters
     * serving that service (minimum one). A planning estimate only; shown to clients as approximate.
     */
    private List<ServiceLoad> serviceLoads(TicketMapper.Lookup lookup, List<Ticket> open, List<CounterDto> counterList,
                                           UUID only) {
        Map<UUID, Long> waitingBy = open.stream().filter(t -> t.getStatus() == TicketStatus.WAITING)
                .collect(Collectors.groupingBy(Ticket::getSiteServiceId, Collectors.counting()));
        return lookup.services().values().stream()
                .filter(s -> only == null || s.getId().equals(only))
                .sorted(Comparator.comparingInt(SiteService::getSortOrder))
                .map(s -> {
                    long waiting = waitingBy.getOrDefault(s.getId(), 0L);
                    long servers = Math.max(1, activeServers(counterList, s.getId()));
                    int est = (int) Math.ceil((double) waiting * s.getEstimatedDurationMinutes() / servers);
                    return new ServiceLoad(s.getId(), s.getServiceItem().getName(), waiting, est);
                }).toList();
    }

    private static long activeServers(List<CounterDto> counterList, UUID siteServiceId) {
        return counterList.stream().filter(c -> c.active() && c.agentStatus() == AgentSession.Status.AVAILABLE
                && c.siteServiceIds().contains(siteServiceId)).count();
    }

    private QueueStats stats(UUID siteId, LocalDate today) {
        return jdbc.queryForObject("""
                select count(*),
                       count(*) filter (where status = 'COMPLETED'),
                       count(*) filter (where status = 'WAITING'),
                       avg(extract(epoch from (first_called_at - issued_at)) / 60.0)
                           filter (where first_called_at is not null)
                from ticket where site_id = ? and business_date = ?
                """, (rs, i) -> new QueueStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getObject(4) == null ? null : Math.round(rs.getDouble(4) * 10) / 10.0),
                siteId, today);
    }

    private static List<Ticket> filter(List<Ticket> list, TicketStatus status) {
        return list.stream().filter(t -> t.getStatus() == status).toList();
    }

    private static SiteRef ref(Site s) {
        return new SiteRef(s.getId(), s.getCode(), s.getName());
    }
}
