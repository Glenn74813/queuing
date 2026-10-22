package ph.gov.philhealth.qms.sync;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/** Wire format between site and central nodes. */
public final class SyncEnvelope {

    private SyncEnvelope() {
    }

    public record Entry(@NotBlank String idempotencyKey, @NotBlank String siteCode, @NotBlank String eventType,
                        @NotBlank String aggregateType, @NotBlank String aggregateId, @NotBlank String payload,
                        @NotBlank String payloadSha256, Instant createdAt) {
    }

    public record Batch(List<Entry> entries) {
    }

    public record Rejected(String key, String reason) {
    }

    public record Result(List<String> accepted, List<String> duplicates, List<Rejected> rejected) {
    }
}
