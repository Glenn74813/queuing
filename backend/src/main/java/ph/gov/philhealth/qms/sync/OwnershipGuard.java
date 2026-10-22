package ph.gov.philhealth.qms.sync;

import org.springframework.stereotype.Component;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.config.QmsProperties;

/**
 * Data ownership rule: regions, sites, the national service catalog and priority categories are
 * owned by the central node. When a site node is synchronized, local edits to them are refused
 * because the next configuration pull would overwrite them (see docs/sync-design.md).
 */
@Component
public class OwnershipGuard {

    private final QmsProperties props;

    public OwnershipGuard(QmsProperties props) {
        this.props = props;
    }

    public void requireCentralOwnedEditable() {
        if (!props.node().isCentral() && props.sync().enabled()) {
            throw ApiException.forbidden("This data is managed centrally. Make the change on the central node.");
        }
    }
}
