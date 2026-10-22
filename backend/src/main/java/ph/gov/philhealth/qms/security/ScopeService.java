package ph.gov.philhealth.qms.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;

/**
 * Enforces organizational scope. Every service method that touches site data must call one of
 * these checks; the JWT alone is never treated as authorization.
 */
@Service
public class ScopeService {

    private final SiteRepository sites;

    public ScopeService(SiteRepository sites) {
        this.sites = sites;
    }

    /** Throws FORBIDDEN unless the current user holds {@code p} for the given site. Returns the site. */
    @Transactional(readOnly = true)
    public Site requireSite(Permission p, UUID siteId) {
        AuthUser user = CurrentUser.get();
        Site site = sites.findById(siteId).orElseThrow(() -> ApiException.notFound("Site"));
        if (!user.canAccessSite(p, site.getId(), site.getRegionId())) {
            throw ApiException.forbidden("Missing " + p + " for site " + site.getCode());
        }
        return site;
    }

    public void requireRegion(Permission p, UUID regionId) {
        AuthUser user = CurrentUser.get();
        boolean ok = user.grants().stream().anyMatch(g -> g.permissions().contains(p)
                && (g.scope() == ScopeType.NATIONAL || (g.scope() == ScopeType.REGION && regionId.equals(g.regionId()))));
        if (!ok) {
            throw ApiException.forbidden("Missing " + p + " for the region");
        }
    }

    public void requireNational(Permission p) {
        if (!CurrentUser.get().hasNational(p)) {
            throw ApiException.forbidden("Missing national-level " + p);
        }
    }

    /** Site ids the current user may access with {@code p}; {@code null} means all sites. */
    @Transactional(readOnly = true)
    public Set<UUID> accessibleSiteIds(Permission p) {
        AuthUser user = CurrentUser.get();
        if (user.hasNational(p)) {
            return null;
        }
        return sites.findAll().stream()
                .filter(s -> user.canAccessSite(p, s.getId(), s.getRegionId()))
                .map(Site::getId)
                .collect(Collectors.toSet());
    }
}
