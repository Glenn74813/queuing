package ph.gov.philhealth.qms.security;

import java.util.UUID;

/** A kiosk or queue display authenticated with its device API key. */
public record DevicePrincipal(UUID deviceId, UUID siteId, String code, String type) {
}
