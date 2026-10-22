package ph.gov.philhealth.qms.queue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketEventRepository extends JpaRepository<TicketEvent, UUID> {
    List<TicketEvent> findByTicketIdOrderByAtAsc(UUID ticketId);
}
