package ph.gov.philhealth.qms.counter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class CounterDtos {

    private CounterDtos() {
    }

    public record AgentRef(UUID id, String fullName) {
    }

    public record CounterDto(UUID id, UUID siteId, String code, String name, boolean active, Set<UUID> siteServiceIds,
                             AgentRef currentAgent, AgentSession.Status agentStatus) {
    }

    public record CounterInput(@NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
                               @NotBlank @Size(max = 60) String name, boolean active,
                               @NotNull Set<UUID> siteServiceIds) {
    }

    public record DeviceDto(UUID id, UUID siteId, String code, String name, Device.Type type, boolean active,
                            Instant lastSeenAt) {
        public static DeviceDto of(Device d) {
            return new DeviceDto(d.getId(), d.getSiteId(), d.getCode(), d.getName(), d.getType(), d.isActive(),
                    d.getLastSeenAt());
        }
    }

    public record DeviceInput(@NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
                              @NotBlank @Size(max = 100) String name, @NotNull Device.Type type) {
    }

    public record DeviceUpdate(@NotBlank @Size(max = 100) String name, boolean active) {
    }

    public record DeviceWithKey(DeviceDto device, String apiKey) {
    }
}
