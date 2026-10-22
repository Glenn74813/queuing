package ph.gov.philhealth.qms.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.PageResponse;
import ph.gov.philhealth.qms.report.ReportDtos.ExceptionRow;
import ph.gov.philhealth.qms.report.ReportDtos.Filter;
import ph.gov.philhealth.qms.report.ReportDtos.GroupBy;
import ph.gov.philhealth.qms.report.ReportDtos.SummaryReport;
import ph.gov.philhealth.qms.report.ReportDtos.SummaryRow;
import ph.gov.philhealth.qms.report.ReportDtos.TicketReportRow;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

/**
 * Performance metrics computed directly from ticket records, so every figure is traceable to source
 * transactions (see the ticket-level report). Metric definitions: docs/metrics-definitions.md.
 * All SQL is parameterized; group-by expressions come from a fixed whitelist.
 */
@Service
public class ReportService {

    private static final int MAX_RANGE_DAYS = 400;
    public static final int EXPORT_MAX_ROWS = 100_000;

    private static final String FROM = """
            from ticket t
            join site s on s.id = t.site_id
            join region r on r.id = s.region_id
            join site_service ss on ss.id = t.original_site_service_id
            join service_item si on si.id = ss.service_item_id
            left join device d on d.id = t.device_id
            left join feedback f on f.ticket_id = t.id
            """;

    private static final Map<GroupBy, String[]> GROUPS = Map.ofEntries(
            Map.entry(GroupBy.SITE, new String[] {"s.code", "s.name"}),
            Map.entry(GroupBy.REGION, new String[] {"r.code", "r.name"}),
            Map.entry(GroupBy.AREA, new String[] {"coalesce(s.area, '-')", "coalesce(s.area, 'Unassigned')"}),
            Map.entry(GroupBy.SERVICE, new String[] {"si.code", "si.name"}),
            Map.entry(GroupBy.CLIENT_TYPE, new String[] {"si.client_type", "si.client_type"}),
            Map.entry(GroupBy.SERVICE_TYPE, new String[] {"si.service_type", "si.service_type"}),
            Map.entry(GroupBy.CLASSIFICATION, new String[] {"si.classification", "si.classification"}),
            Map.entry(GroupBy.MULTI_STAGE, new String[] {"(t.stage_no > 1)::text",
                    "case when t.stage_no > 1 then 'Multi-stage' else 'Single stage' end"}),
            Map.entry(GroupBy.CHANNEL, new String[] {"t.channel", "t.channel"}),
            Map.entry(GroupBy.DEVICE, new String[] {"coalesce(d.code, '-')", "coalesce(d.name, 'No device')"}),
            Map.entry(GroupBy.STATUS, new String[] {"t.status", "t.status"}),
            Map.entry(GroupBy.DAY, new String[] {"t.business_date::text", "t.business_date::text"}));

    private static final String METRICS = """
            count(*) as issued,
            count(*) filter (where t.status = 'COMPLETED') as served,
            count(*) filter (where t.status in ('REMOVED', 'EXPIRED')) as abandoned,
            count(*) filter (where t.status = 'VOIDED') as voided,
            count(*) filter (where t.transfer_count > 0) as rerouted,
            avg(extract(epoch from (t.first_called_at - t.issued_at)) / 60.0)
                filter (where t.first_called_at is not null and t.status <> 'VOIDED') as avg_queue,
            avg(t.wait_seconds / 60.0) filter (where t.status = 'COMPLETED') as avg_wait,
            avg(t.service_seconds / 60.0) filter (where t.status = 'COMPLETED') as avg_process,
            count(f.id) as feedback_count,
            avg(f.rating) as avg_rating
            """;

    private final JdbcTemplate jdbc;
    private final ScopeService scope;

    public ReportService(JdbcTemplate jdbc, ScopeService scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public SummaryReport summary(Filter filter, GroupBy groupBy) {
        return summary(where(filter, true), filter, groupBy);
    }

    /**
     * Summary without the caller's scope check, for scheduled reports: the schedule's target scope
     * was authorized when it was created, and each download is authorized again.
     */
    @Transactional(readOnly = true)
    public SummaryReport summaryForSchedule(Filter filter, GroupBy groupBy) {
        return summary(where(filter, false), filter, groupBy);
    }

    private SummaryReport summary(Where where, Filter filter, GroupBy groupBy) {
        String[] g = GROUPS.get(groupBy);
        String sql = "select " + g[0] + " as k, min(" + g[1] + ") as label, " + METRICS + FROM + where.sql
                + " group by " + g[0] + " order by 2";
        List<SummaryRow> rows = jdbc.query(sql, (rs, i) -> row(rs, rs.getString("k"), rs.getString("label")),
                where.args.toArray());
        SummaryRow totals = jdbc.queryForObject("select " + METRICS + FROM + where.sql,
                (rs, i) -> row(rs, "TOTAL", "Total"), where.args.toArray());
        return new SummaryReport(filter.from(), filter.to(), groupBy, rows, totals, Instant.now(),
                ReportDtos.METRIC_DEFINITIONS_VERSION);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketReportRow> tickets(Filter filter, int page, int size) {
        Where where = where(filter, true);
        int pageSize = Math.min(Math.max(size, 1), PageResponse.MAX_PAGE_SIZE);
        long total = jdbc.queryForObject("select count(*) " + FROM + where.sql, Long.class, where.args.toArray());
        List<TicketReportRow> rows = ticketRows(where, pageSize, (long) Math.max(page, 0) * pageSize);
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new PageResponse<>(rows, Math.max(page, 0), pageSize, total, totalPages);
    }

    /** All ticket rows for a download; refuses result sets above {@link #EXPORT_MAX_ROWS}. */
    @Transactional(readOnly = true)
    public List<TicketReportRow> ticketsForExport(Filter filter) {
        Where where = where(filter, true);
        long total = jdbc.queryForObject("select count(*) " + FROM + where.sql, Long.class, where.args.toArray());
        if (total > EXPORT_MAX_ROWS) {
            throw ApiException.badRequest("The export would contain " + total + " tickets; the limit is "
                    + EXPORT_MAX_ROWS + ". Narrow the date range or filters.");
        }
        return ticketRows(where, EXPORT_MAX_ROWS, 0);
    }

    private List<TicketReportRow> ticketRows(Where where, int limit, long offset) {
        List<Object> args = new ArrayList<>(where.args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                select t.*, s.name as site_name, r.name as region_name, cur.name as service_name,
                       si.client_type, si.service_type, si.classification, pc.code as priority_code,
                       c.name as counter_name, d.code as device_code
                """ + FROM + """
                join site_service css on css.id = t.site_service_id
                join service_item cur on cur.id = css.service_item_id
                left join priority_category pc on pc.id = t.priority_category_id
                left join service_counter c on c.id = t.counter_id
                """ + where.sql + " order by t.issued_at limit ? offset ?", (rs, i) -> new TicketReportRow(
                rs.getObject("id", UUID.class), rs.getString("display_number"),
                rs.getObject("business_date", java.time.LocalDate.class), rs.getObject("site_id", UUID.class),
                rs.getString("site_name"), rs.getString("region_name"), rs.getString("service_name"),
                rs.getString("client_type"), rs.getString("service_type"), rs.getString("classification"),
                rs.getString("priority_code"), rs.getString("channel"), rs.getString("status"),
                rs.getInt("stage_no"), rs.getString("counter_name"), rs.getString("agent_name"),
                instant(rs, "issued_at"), instant(rs, "first_called_at"), instant(rs, "completed_at"),
                rs.getTimestamp("first_called_at") == null ? null
                        : ChronoUnit.SECONDS.between(instant(rs, "issued_at"), instant(rs, "first_called_at")),
                rs.getLong("wait_seconds"), rs.getLong("service_seconds"), rs.getLong("hold_seconds"),
                rs.getInt("transfer_count"), rs.getInt("recall_count"), rs.getString("device_code")),
                args.toArray());
    }

    /**
     * Agent performance against the SLA of each site's region (national default when the region has
     * none; per-service overrides take precedence). Built from the ticket event history so that each
     * agent is credited only with their own time at the counter:
     * <ul>
     *   <li>a handled transaction = one ticket, at one service stage, called by the agent;</li>
     *   <li>processing time = time in CALLED from each CALL/RESUME by the agent until the next
     *       status change (recalls do not interrupt it; holds do);</li>
     *   <li>waiting time = time in the queue before the agent's call (since issue or transfer).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public ReportDtos.AgentPerformanceReport agentPerformance(Filter filter) {
        Where where = where(filter, true);
        List<ReportDtos.AgentPerformanceRow> rows = jdbc.query(agentCtes(where) + AGENT_METRICS + """
                from targeted x
                join site s on s.id = x.site_id
                join region r on r.id = s.region_id
                left join app_user u on u.id = x.actor_user_id
                left join (select actor_user_id, site_id, sum(n) as n from recalls group by actor_user_id, site_id) rc
                       on rc.actor_user_id = x.actor_user_id and rc.site_id = x.site_id
                group by x.actor_user_id, x.site_id
                order by max(s.name), max(x.actor_name)
                """, (rs, i) -> agentRow(rs), where.args.toArray());
        return new ReportDtos.AgentPerformanceReport(filter.from(), filter.to(), rows, Instant.now());
    }

    /**
     * Everything for the agent performance workbook: every active agent and supervisor in scope
     * (with or without activity), a per-day breakdown and the individual transactions behind it.
     */
    @Transactional(readOnly = true)
    public ReportDtos.AgentWorkbook agentWorkbook(Filter filter) {
        Where where = where(filter, true);
        List<ReportDtos.AgentPerformanceRow> active = agentPerformance(filter).rows();
        java.util.Set<String> seen = new java.util.HashSet<>();
        active.forEach(r -> seen.add(r.agentId() + ":" + r.siteId()));
        List<ReportDtos.AgentPerformanceRow> all = new ArrayList<>(active);
        inactiveAgents(filter).stream().filter(r -> !seen.contains(r.agentId() + ":" + r.siteId())).forEach(all::add);
        all.sort(java.util.Comparator.comparing(ReportDtos.AgentPerformanceRow::siteName)
                .thenComparing(ReportDtos.AgentPerformanceRow::agentName, String.CASE_INSENSITIVE_ORDER));

        List<ReportDtos.AgentDailyRow> daily = jdbc.query(agentCtes(where) + AGENT_METRICS + """
                , x.business_date as day
                from targeted x
                join site s on s.id = x.site_id
                join region r on r.id = s.region_id
                left join app_user u on u.id = x.actor_user_id
                left join recalls rc on rc.actor_user_id = x.actor_user_id and rc.site_id = x.site_id
                                    and rc.business_date = x.business_date
                group by x.actor_user_id, x.site_id, x.business_date
                order by x.business_date, max(s.name), max(x.actor_name)
                """, (rs, i) -> new ReportDtos.AgentDailyRow(rs.getObject("day", LocalDate.class), agentRow(rs)),
                where.args.toArray());

        List<Object> args = new ArrayList<>(where.args);
        args.add(EXPORT_MAX_ROWS + 1);
        List<ReportDtos.AgentTransactionRow> transactions = jdbc.query(agentCtes(where) + """
                select x.business_date, x.display_number, x.actor_name, u.username, s.name as site_name,
                       si.name as service_name, x.wait_secs, x.wait_target, x.processing_secs, x.proc_target,
                       x.completed, x.transferred, x.removed, x.in_progress
                from targeted x
                join site s on s.id = x.site_id
                join service_item si on si.id = x.service_item_id
                left join app_user u on u.id = x.actor_user_id
                order by x.business_date, s.name, x.actor_name, x.display_number
                limit ?
                """, (rs, i) -> {
            Double wait = rs.getObject("wait_secs") == null ? null : Math.round(rs.getDouble("wait_secs") / 6.0) / 10.0;
            double proc = Math.round(rs.getDouble("processing_secs") / 6.0) / 10.0;
            Integer waitTarget = (Integer) rs.getObject("wait_target");
            Integer procTarget = (Integer) rs.getObject("proc_target");
            String outcome = rs.getBoolean("completed") ? "Completed" : rs.getBoolean("transferred") ? "Transferred"
                    : rs.getBoolean("removed") ? "No-show (removed)" : rs.getBoolean("in_progress") ? "In progress" : "Other";
            boolean counted = !rs.getBoolean("in_progress") && !rs.getBoolean("removed");
            return new ReportDtos.AgentTransactionRow(rs.getObject("business_date", LocalDate.class),
                    rs.getString("display_number"), rs.getString("actor_name"), rs.getString("username"),
                    rs.getString("site_name"), rs.getString("service_name"), wait, waitTarget,
                    wait == null || waitTarget == null ? null : rs.getDouble("wait_secs") <= waitTarget * 60.0,
                    proc, procTarget,
                    !counted || procTarget == null ? null : rs.getDouble("processing_secs") <= procTarget * 60.0,
                    outcome);
        }, args.toArray());
        if (transactions.size() > EXPORT_MAX_ROWS) {
            throw ApiException.badRequest("The workbook would contain more than " + EXPORT_MAX_ROWS
                    + " transactions. Narrow the date range or choose a site.");
        }
        return new ReportDtos.AgentWorkbook(filter.from(), filter.to(), all, daily, transactions, Instant.now());
    }

    /** Active agents and supervisors assigned to sites in the filter's scope (for "no activity" rows). */
    private List<ReportDtos.AgentPerformanceRow> inactiveAgents(Filter f) {
        StringBuilder sql = new StringBuilder("""
                select u.id, u.full_name, u.username, s.id as site_id, s.name as site_name, r.name as region_name,
                       coalesce(rp.waiting_target_minutes, np.waiting_target_minutes) as policy_wait,
                       coalesce(rp.processing_target_minutes, np.processing_target_minutes) as policy_proc,
                       coalesce(rp.compliance_target_percent, np.compliance_target_percent) as compliance
                from user_role_assignment a
                join app_user u on u.id = a.user_id
                join site s on s.id = a.site_id
                join region r on r.id = s.region_id
                left join sla_policy rp on rp.region_id = s.region_id
                left join sla_policy np on np.region_id is null
                where a.role_code in ('AGENT', 'SUPERVISOR') and u.status = 'ACTIVE' and s.active
                """);
        List<Object> args = new ArrayList<>();
        if (f.siteId() != null) {
            sql.append(" and s.id = ?");
            args.add(f.siteId());
        } else {
            Set<UUID> allowed = scope.accessibleSiteIds(Permission.REPORT_VIEW);
            if (allowed != null) {
                sql.append(" and s.id = any(?)");
                args.add(allowed.toArray(UUID[]::new));
            }
        }
        if (f.regionId() != null) {
            sql.append(" and s.region_id = ?");
            args.add(f.regionId());
        }
        return jdbc.query(sql.toString(), (rs, i) -> new ReportDtos.AgentPerformanceRow(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), 0, 0, 0,
                0, 0, null, null, null, null, (Integer) rs.getObject(7), (Integer) rs.getObject(8), round(rs, "compliance"),
                false, ReportDtos.SlaStatus.NO_DATA), args.toArray());
    }

    /** Shared CTEs: handled transactions per agent with the SLA targets that apply to each. */
    private static String agentCtes(Where where) {
        return """
                with base as (
                  select e.ticket_id, e.event_type, e.at, e.id as event_id, e.actor_user_id, e.actor_name,
                         t.site_id, s.region_id, es.service_item_id, t.business_date, t.display_number
                  from ticket_event e
                  join ticket t on t.id = e.ticket_id
                  join site s on s.id = t.site_id
                  join site_service ss on ss.id = t.original_site_service_id
                  join service_item si on si.id = ss.service_item_id
                  join site_service es on es.id = e.site_service_id
                """ + where.sql + """
                ),
                seq as (
                  select b.*, lead(b.at) over w as next_at, lead(b.event_type) over w as next_type,
                         lag(b.at) over w as prev_at
                  from base b where b.event_type <> 'RECALL'
                  window w as (partition by b.ticket_id order by b.at, b.event_id)
                ),
                handled as (
                  select ticket_id, actor_user_id, max(actor_name) as actor_name, site_id, region_id, service_item_id,
                         business_date, display_number,
                         sum(extract(epoch from (coalesce(next_at, now()) - at))) as processing_secs,
                         bool_or(next_type = 'COMPLETE') as completed,
                         bool_or(next_type = 'TRANSFER') as transferred,
                         bool_or(next_type = 'REMOVE') as removed,
                         bool_or(next_type is null) as in_progress,
                         min(case when event_type = 'CALL' then extract(epoch from (at - prev_at)) end) as wait_secs
                  from seq
                  where event_type in ('CALL', 'RESUME') and actor_user_id is not null
                  group by ticket_id, actor_user_id, site_id, region_id, service_item_id, business_date, display_number
                ),
                targeted as (
                  select h.*,
                         coalesce(st.waiting_target_minutes, coalesce(rp.waiting_target_minutes, np.waiting_target_minutes)) as wait_target,
                         coalesce(st.processing_target_minutes, coalesce(rp.processing_target_minutes, np.processing_target_minutes)) as proc_target,
                         coalesce(rp.waiting_target_minutes, np.waiting_target_minutes) as policy_wait,
                         coalesce(rp.processing_target_minutes, np.processing_target_minutes) as policy_proc,
                         coalesce(rp.compliance_target_percent, np.compliance_target_percent) as compliance,
                         st.id is not null as overridden
                  from handled h
                  left join sla_policy rp on rp.region_id = h.region_id
                  left join sla_policy np on np.region_id is null
                  left join sla_service_target st on st.policy_id = coalesce(rp.id, np.id)
                                                 and st.service_item_id = h.service_item_id
                ),
                recalls as (
                  select actor_user_id, site_id, business_date, count(*) as n from base
                  where event_type = 'RECALL' and actor_user_id is not null
                  group by actor_user_id, site_id, business_date
                )
                """;
    }

    /** Per-agent metric columns over the {@code targeted} CTE (x) joined with site s, region r, user u, recalls rc. */
    private static final String AGENT_METRICS = """
            select x.actor_user_id, max(x.actor_name) as agent_name, max(u.username) as username, x.site_id,
                   max(s.name) as site_name, max(r.name) as region_name,
                   count(*) as handled,
                   count(*) filter (where x.completed) as completed,
                   count(*) filter (where x.transferred) as transferred,
                   count(*) filter (where x.removed) as no_shows,
                   coalesce(max(rc.n), 0) as recalls,
                   avg(x.processing_secs / 60.0) filter (where not x.in_progress and not x.removed) as avg_proc,
                   100.0 * count(*) filter (where not x.in_progress and not x.removed and x.proc_target is not null
                                              and x.processing_secs <= x.proc_target * 60)
                         / nullif(count(*) filter (where not x.in_progress and not x.removed and x.proc_target is not null), 0) as proc_pct,
                   avg(x.wait_secs / 60.0) filter (where x.wait_secs is not null) as avg_wait,
                   100.0 * count(*) filter (where x.wait_secs is not null and x.wait_target is not null
                                              and x.wait_secs <= x.wait_target * 60)
                         / nullif(count(*) filter (where x.wait_secs is not null and x.wait_target is not null), 0) as wait_pct,
                   max(x.policy_wait) as policy_wait, max(x.policy_proc) as policy_proc,
                   max(x.compliance) as compliance, bool_or(x.overridden) as overridden
            """;

    private static ReportDtos.AgentPerformanceRow agentRow(ResultSet rs) throws SQLException {
        Double procPct = round(rs, "proc_pct");
        Double compliance = round(rs, "compliance");
        ReportDtos.SlaStatus status = procPct == null || compliance == null ? ReportDtos.SlaStatus.NO_DATA
                : procPct >= compliance ? ReportDtos.SlaStatus.MET : ReportDtos.SlaStatus.NOT_MET;
        return new ReportDtos.AgentPerformanceRow(rs.getObject("actor_user_id", UUID.class),
                rs.getString("agent_name"), rs.getString("username"), rs.getObject("site_id", UUID.class),
                rs.getString("site_name"), rs.getString("region_name"), rs.getLong("handled"),
                rs.getLong("completed"), rs.getLong("transferred"), rs.getLong("no_shows"), rs.getLong("recalls"),
                round(rs, "avg_proc"), procPct, round(rs, "avg_wait"), round(rs, "wait_pct"),
                (Integer) rs.getObject("policy_wait"), (Integer) rs.getObject("policy_proc"), compliance,
                rs.getBoolean("overridden"), status);
    }

    /** Data-validation checks; each row names a ticket and the inconsistency found. */
    @Transactional(readOnly = true)
    public List<ExceptionRow> exceptions(Filter filter) {
        Where where = where(filter, true);
        return jdbc.query("""
                select t.id, t.display_number, s.name as site_name, t.business_date, x.issue
                """ + FROM + """
                cross join lateral (values
                    (case when t.status = 'COMPLETED' and t.first_called_at is null
                          then 'Completed without being called' end),
                    (case when t.completed_at is not null and t.completed_at < t.issued_at
                          then 'Completion time before issue time' end),
                    (case when t.first_called_at is not null and t.first_called_at < t.issued_at
                          then 'Call time before issue time' end),
                    (case when t.status in ('WAITING', 'CALLED', 'ON_HOLD')
                               and t.business_date < (now() at time zone s.timezone)::date
                          then 'Still open after its business day (expiry job not run)' end),
                    (case when t.status = 'CALLED' and t.counter_id is null
                          then 'Called ticket without a counter' end),
                    (case when t.status = 'COMPLETED'
                               and t.service_seconds > 4 * 3600 then 'Processing time above 4 hours' end),
                    (case when not exists (select 1 from ticket_event e where e.ticket_id = t.id)
                          then 'No event history' end)
                ) as x(issue)
                """ + where.sql + " and x.issue is not null order by t.business_date, t.display_number limit 5000",
                (rs, i) -> new ExceptionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, java.time.LocalDate.class), rs.getString(5)),
                where.args.toArray());
    }

    private record Where(String sql, List<Object> args) {
    }

    private Where where(Filter f, boolean enforceScope) {
        if (f.from() == null || f.to() == null || f.to().isBefore(f.from())) {
            throw ApiException.badRequest("A valid from/to date range is required");
        }
        if (ChronoUnit.DAYS.between(f.from(), f.to()) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Date range may not exceed " + MAX_RANGE_DAYS + " days");
        }
        StringBuilder sql = new StringBuilder(" where t.business_date between ? and ?");
        List<Object> args = new ArrayList<>(List.of(f.from(), f.to()));
        if (f.siteId() != null) {
            if (enforceScope) {
                scope.requireSite(Permission.REPORT_VIEW, f.siteId());
            }
            sql.append(" and t.site_id = ?");
            args.add(f.siteId());
        } else if (enforceScope) {
            Set<UUID> allowed = scope.accessibleSiteIds(Permission.REPORT_VIEW);
            if (allowed != null) {
                if (allowed.isEmpty()) {
                    throw ApiException.forbidden("No sites in your reporting scope");
                }
                sql.append(" and t.site_id = any(?)");
                args.add(allowed.toArray(UUID[]::new));
            }
        }
        if (f.regionId() != null) {
            sql.append(" and s.region_id = ?");
            args.add(f.regionId());
        }
        addEnum(sql, args, "si.client_type", f.clientType(), Set.of("G2C", "G2B", "G2G"));
        addEnum(sql, args, "si.service_type", f.serviceType(), Set.of("INTERNAL", "EXTERNAL"));
        addEnum(sql, args, "si.classification", f.classification(),
                Set.of("SIMPLE", "COMPLEX", "HIGHLY_TECHNICAL_A", "HIGHLY_TECHNICAL_B", "OTHER"));
        addEnum(sql, args, "t.channel", f.channel(), Set.of("PRINTED", "ELECTRONIC"));
        return new Where(sql.toString(), args);
    }

    private static void addEnum(StringBuilder sql, List<Object> args, String column, String value, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!allowed.contains(value)) {
            throw ApiException.badRequest("Invalid filter value " + value);
        }
        sql.append(" and ").append(column).append(" = ?");
        args.add(value);
    }

    private static SummaryRow row(ResultSet rs, String key, String label) throws SQLException {
        return new SummaryRow(key, label, rs.getLong("issued"), rs.getLong("served"), rs.getLong("abandoned"),
                rs.getLong("voided"), rs.getLong("rerouted"), round(rs, "avg_queue"), round(rs, "avg_wait"),
                round(rs, "avg_process"), rs.getLong("feedback_count"), round(rs, "avg_rating"));
    }

    private static Double round(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : Math.round(v * 100) / 100.0;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
