package ph.gov.philhealth.qms.queue;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Append-only history of a ticket: who did what, when, at which counter and why. */
@Entity
@Table(name = "ticket_event")
@Getter
@Setter
public class TicketEvent {
    @Id
    private UUID id;
    private UUID ticketId;
    private UUID siteId;

    @Enumerated(EnumType.STRING)
    private TicketAction eventType;

    @Enumerated(EnumType.STRING)
    private TicketStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private TicketStatus toStatus;

    private Instant at;
    private UUID actorUserId;
    private String actorName;
    private UUID counterId;
    private UUID siteServiceId;
    private String reason;
}
