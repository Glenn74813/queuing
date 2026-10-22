package ph.gov.philhealth.qms.sync;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Named synchronization checkpoints (e.g. last config pull watermark). */
@Entity
@Table(name = "sync_state")
@Getter
@Setter
public class SyncState {
    @Id
    private String name;
    private String value;
    private Instant updatedAt;
}
