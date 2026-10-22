package ph.gov.philhealth.qms.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.queue.QueueDtos.PublicFeedbackRequest;
import ph.gov.philhealth.qms.queue.QueueDtos.PublicTicketStatus;
import ph.gov.philhealth.qms.security.LoginRateLimiter;

/** The client's phone view of their ticket, opened from the QR code; the token is the only credential. */
@RestController
public class PublicTicketController {

    private final TicketLinkService links;
    private final QueueQueryService queries;
    private final FeedbackService feedback;
    private final LoginRateLimiter limiter;

    public PublicTicketController(TicketLinkService links, QueueQueryService queries, FeedbackService feedback,
                                  LoginRateLimiter limiter) {
        this.links = links;
        this.queries = queries;
        this.feedback = feedback;
        this.limiter = limiter;
    }

    @GetMapping("/api/v1/public/tickets/{token}")
    public PublicTicketStatus status(@PathVariable String token) {
        Ticket t = links.resolve(token);
        return queries.publicStatus(t, feedback.given(t.getId()));
    }

    @PostMapping("/api/v1/public/tickets/{token}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable String token, @Valid @RequestBody PublicFeedbackRequest req,
                                         HttpServletRequest http) {
        limiter.check("feedback:" + http.getRemoteAddr());
        feedback.submit(links.resolve(token), req.rating(), req.comment());
        return ResponseEntity.noContent().build();
    }
}
