package ph.gov.philhealth.qms.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authenticated staff user. Loaded from the database on every request, so suspensions and
 * permission changes take effect immediately even while an access token is still valid.
 */
public record AuthUser(UUID id, String username, String fullName, boolean mustChangePassword, List<Grant> grants) {

    /** One role assignment with its geographic scope and the permissions it carries. */
    public record Grant(String roleCode, int roleLevel, ScopeType scope, UUID regionId, UUID siteId,
                        Set<Permission> permissions) {

        /** Whether this grant covers the given site (located in the given region). */
        public boolean covers(UUID targetSiteId, UUID targetRegionId) {
            return switch (scope) {
                case NATIONAL -> true;
                case REGION -> regionId != null && regionId.equals(targetRegionId);
                case SITE -> siteId != null && siteId.equals(targetSiteId);
            };
        }
    }

    public Set<Permission> permissions() {
        Set<Permission> all = EnumSet.noneOf(Permission.class);
        grants.forEach(g -> all.addAll(g.permissions()));
        return all;
    }

    public boolean has(Permission p) {
        return grants.stream().anyMatch(g -> g.permissions().contains(p));
    }

    public boolean hasNational(Permission p) {
        return grants.stream().anyMatch(g -> g.scope() == ScopeType.NATIONAL && g.permissions().contains(p));
    }

    public boolean canAccessSite(Permission p, UUID siteId, UUID regionId) {
        return grants.stream().anyMatch(g -> g.permissions().contains(p) && g.covers(siteId, regionId));
    }

    public int maxLevel() {
        return grants.stream().mapToInt(Grant::roleLevel).max().orElse(0);
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).collect(Collectors.toSet());
    }
}
