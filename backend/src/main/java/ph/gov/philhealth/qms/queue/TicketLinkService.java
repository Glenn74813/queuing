package ph.gov.philhealth.qms.queue;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.org.SiteRepository;

/**
 * Phone e-ticket links. The kiosk response carries a random 128-bit token (shown as a QR code and
 * printed on the ticket); only its SHA-256 is stored, through JDBC so JPA updates of the ticket never
 * overwrite it. Each issue response gets a fresh token, so a kiosk retry still receives a working link.
 */
@Service
public class TicketLinkService {

    /** Links stop working this many days after the ticket's business date. */
    static final int LINK_DAYS = 2;

    private final JdbcTemplate jdbc;
    private final TicketRepository tickets;
    private final SiteRepository sites;
    private final TimeService time;

    public TicketLinkService(JdbcTemplate jdbc, TicketRepository tickets, SiteRepository sites, TimeService time) {
        this.jdbc = jdbc;
        this.tickets = tickets;
        this.sites = sites;
        this.time = time;
    }

    @Transactional
    public String newToken(UUID ticketId) {
        String raw = Hashing.randomToken(16);
        jdbc.update("update ticket set public_token_hash = ? where id = ?", Hashing.sha256Hex(raw), ticketId);
        return raw;
    }

    /** The ticket a link points to; 404 for unknown, malformed or expired links alike. */
    @Transactional(readOnly = true)
    public Ticket resolve(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{16,64}$")) {
            throw ApiException.notFound("Ticket");
        }
        Optional<UUID> id = jdbc.query("select id from ticket where public_token_hash = ?",
                (rs, i) -> rs.getObject(1, UUID.class), Hashing.sha256Hex(token)).stream().findFirst();
        Ticket t = id.flatMap(tickets::findById).orElseThrow(() -> ApiException.notFound("Ticket"));
        String zone = sites.findById(t.getSiteId()).orElseThrow().getTimezone();
        LocalDate today = time.businessDate(zone);
        if (t.getBusinessDate().isBefore(today.minusDays(LINK_DAYS))) {
            throw ApiException.notFound("Ticket");
        }
        return t;
    }

    /** Today's completed ticket at the site with this number and no feedback yet (kiosk "Rate our service"). */
    @Transactional(readOnly = true)
    public Ticket completedToday(UUID siteId, String displayNumber) {
        String zone = sites.findById(siteId).orElseThrow().getTimezone();
        // Compared without separators, so "A004" finds A-004.
        String number = displayNumber == null ? "" : displayNumber.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return jdbc.query("""
                        select t.id from ticket t
                        where t.site_id = ? and t.business_date = ? and regexp_replace(upper(t.display_number), '[^A-Z0-9]', '', 'g') = ?
                          and t.status = 'COMPLETED' and not exists (select 1 from feedback f where f.ticket_id = t.id)
                        """, (rs, i) -> rs.getObject(1, UUID.class), siteId, time.businessDate(zone), number)
                .stream().findFirst().flatMap(tickets::findById)
                .orElseThrow(() -> ApiException.notFound("Completed ticket"));
    }
}
