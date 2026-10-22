package ph.gov.philhealth.qms.queue;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {
    boolean existsByTicketId(UUID ticketId);
}
