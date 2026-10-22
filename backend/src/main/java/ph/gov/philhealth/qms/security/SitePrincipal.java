package ph.gov.philhealth.qms.security;

import java.util.UUID;

/** A site node authenticated to the central node with its synchronization key. */
public record SitePrincipal(UUID siteId, String siteCode) {
}
