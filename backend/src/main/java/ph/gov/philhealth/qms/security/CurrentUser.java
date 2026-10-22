package ph.gov.philhealth.qms.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ph.gov.philhealth.qms.common.ApiException;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser get() {
        return find().orElseThrow(() -> ApiException.forbidden("Staff authentication required"));
    }

    public static Optional<AuthUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static DevicePrincipal device() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DevicePrincipal d) {
            return d;
        }
        throw ApiException.forbidden("Device authentication required");
    }

    public static SitePrincipal site() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SitePrincipal s) {
            return s;
        }
        throw ApiException.forbidden("Site authentication required");
    }
}
