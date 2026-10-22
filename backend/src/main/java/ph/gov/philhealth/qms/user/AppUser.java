package ph.gov.philhealth.qms.user;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser extends BaseEntity {

    public enum Status { ACTIVE, SUSPENDED, DEACTIVATED }

    private String username;
    private String fullName;
    private String email;
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    private boolean mustChangePassword = true;
    private int failedAttempts;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant passwordChangedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RoleAssignment> assignments = new ArrayList<>();

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
