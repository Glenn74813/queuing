package ph.gov.philhealth.qms.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;

/** Closes tickets left open after their business day so they are counted as abandoned (EXPIRED). */
@Component
public class TicketExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(TicketExpiryJob.class);

    private final QueueService queue;
    private final SiteRepository sites;
    private final QmsProperties props;

    public TicketExpiryJob(QueueService queue, SiteRepository sites, QmsProperties props) {
        this.queue = queue;
        this.sites = sites;
        this.props = props;
    }

    @Scheduled(cron = "${qms.queue.expire-cron}")
    public void run() {
        // A site node only processes its own site; central processes none (sites own their tickets).
        if (props.node().isCentral()) {
            return;
        }
        for (Site site : sites.findAll()) {
            if (props.node().siteCode() != null && !props.node().siteCode().isBlank()
                    && !props.node().siteCode().equals(site.getCode())) {
                continue;
            }
            try {
                int n = queue.expireStale(site);
                if (n > 0) {
                    log.info("Expired {} open tickets from previous business days at site {}", n, site.getCode());
                }
            } catch (RuntimeException e) {
                log.error("Ticket expiry failed for site {}", site.getCode(), e);
            }
        }
    }
}
