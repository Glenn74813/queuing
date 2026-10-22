package ph.gov.philhealth.qms.queue;

import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.queue.QueueDtos.DisplayBoard;
import ph.gov.philhealth.qms.queue.QueueDtos.FeedbackRequest;
import ph.gov.philhealth.qms.queue.QueueDtos.IssueTicketRequest;
import ph.gov.philhealth.qms.queue.QueueDtos.IssuedTicket;
import ph.gov.philhealth.qms.queue.QueueDtos.KioskConfig;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.DevicePrincipal;

/** Endpoints used by kiosks (ticket dispensers) and queue displays, authenticated by device key. */
@RestController
public class DeviceController {

    private final QueueService queue;
    private final QueueQueryService queries;
    private final FeedbackService feedback;
    private final TicketRepository tickets;

    public DeviceController(QueueService queue, QueueQueryService queries, FeedbackService feedback,
                            TicketRepository tickets) {
        this.queue = queue;
        this.queries = queries;
        this.feedback = feedback;
        this.tickets = tickets;
    }

    @GetMapping("/api/v1/kiosk/config")
    public KioskConfig kioskConfig() {
        return queries.kioskConfig(CurrentUser.device());
    }

    @PostMapping("/api/v1/kiosk/tickets")
    public IssuedTicket issue(@Valid @RequestBody IssueTicketRequest req) {
        DevicePrincipal device = CurrentUser.device();
        Ticket t;
        try {
            t = queue.issue(device, req.siteServiceId(), req.priorityCategoryId(), req.channel(), req.requestId());
        } catch (DataIntegrityViolationException e) {
            // Two concurrent retries with the same requestId: the loser returns the winner's ticket.
            t = tickets.findByRequestId(req.requestId()).filter(x -> x.getSiteId().equals(device.siteId()))
                    .orElseThrow(() -> e);
        }
        return queries.issued(t);
    }

    @PostMapping("/api/v1/kiosk/feedback")
    public ResponseEntity<Void> feedback(@Valid @RequestBody FeedbackRequest req) {
        feedback.submit(CurrentUser.device(), req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/display/board")
    public DisplayBoard board() {
        return queries.board(CurrentUser.device());
    }
}
