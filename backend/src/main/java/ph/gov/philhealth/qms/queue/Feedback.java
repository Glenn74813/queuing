package ph.gov.philhealth.qms.queue;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "feedback")
@Getter
@Setter
public class Feedback {
    @Id
    private UUID id;
    private UUID ticketId;
    private UUID siteId;
    private short rating;
    private String comment;
    private Instant submittedAt;
}
