package ph.gov.philhealth.qms.queue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.queue.QueueDtos.FeedbackRequest;
import ph.gov.philhealth.qms.security.DevicePrincipal;
import ph.gov.philhealth.qms.sync.OutboxService;
import ph.gov.philhealth.qms.sync.Snapshots;

/**
 * Client feedback per completed transaction. Stored locally and replicated centrally; forwarding to
 * CSMST is done by the integration layer once PhilHealth confirms its interface (docs/integration.md).
 */
@Service
public class FeedbackService {

    private final FeedbackRepository repo;
    private final TicketRepository tickets;
    private final OutboxService outbox;
    private final TimeService time;

    public FeedbackService(FeedbackRepository repo, TicketRepository tickets, OutboxService outbox, TimeService time) {
        this.repo = repo;
        this.tickets = tickets;
        this.outbox = outbox;
        this.time = time;
    }

    @Transactional
    public void submit(DevicePrincipal device, FeedbackRequest req) {
        Ticket t = tickets.findById(req.ticketId()).filter(x -> x.getSiteId().equals(device.siteId()))
                .orElseThrow(() -> ApiException.notFound("Ticket"));
        if (t.getStatus() != TicketStatus.COMPLETED) {
            throw ApiException.conflict("Feedback can be given only for completed transactions");
        }
        if (repo.existsByTicketId(t.getId())) {
            throw ApiException.conflict("Feedback was already submitted for this ticket");
        }
        Feedback f = new Feedback();
        f.setId(Ids.newId());
        f.setTicketId(t.getId());
        f.setSiteId(t.getSiteId());
        f.setRating((short) req.rating());
        f.setComment(req.comment());
        f.setSubmittedAt(time.now());
        repo.save(f);
        outbox.enqueue("FEEDBACK_SUBMITTED", "Feedback", f.getId().toString(), Snapshots.of(f));
    }
}
