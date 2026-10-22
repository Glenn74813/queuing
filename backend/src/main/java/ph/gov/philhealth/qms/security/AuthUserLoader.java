package ph.gov.philhealth.qms.security;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.user.AppUser;
import ph.gov.philhealth.qms.user.AppUserRepository;
import ph.gov.philhealth.qms.user.RoleAssignment;

@Component
public class AuthUserLoader {

    private final AppUserRepository users;

    public AuthUserLoader(AppUserRepository users) {
        this.users = users;
    }

    /** Loads an active user with grants; empty when the user is missing, suspended or deactivated. */
    @Transactional(readOnly = true)
    public Optional<AuthUser> loadActive(UUID userId) {
        return users.findById(userId)
                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                .map(AuthUserLoader::toAuthUser);
    }

    public static AuthUser toAuthUser(AppUser u) {
        var grants = u.getAssignments().stream().map(AuthUserLoader::toGrant).toList();
        return new AuthUser(u.getId(), u.getUsername(), u.getFullName(), u.isMustChangePassword(), grants);
    }

    private static AuthUser.Grant toGrant(RoleAssignment a) {
        ScopeType scope = a.getSite() != null ? ScopeType.SITE
                : a.getRegion() != null ? ScopeType.REGION : ScopeType.NATIONAL;
        var perms = a.getRole().getPermissions().isEmpty() ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(a.getRole().getPermissions());
        return new AuthUser.Grant(a.getRole().getCode(), a.getRole().getLevel(), scope,
                a.getRegion() != null ? a.getRegion().getId() : null,
                a.getSite() != null ? a.getSite().getId() : null, perms);
    }
}
