package ph.gov.philhealth.qms.queue;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ph.gov.philhealth.qms.queue.QueueDtos.QueueMessage;

/** Publishes queue changes to /topic/sites/{siteId}/queue only after the change is committed. */
@Component
public class QueueBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(QueueBroadcaster.class);

    private final SimpMessagingTemplate messaging;

    public QueueBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void publishAfterCommit(QueueMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(message);
                }
            });
        } else {
            send(message);
        }
    }

    private void send(QueueMessage message) {
        try {
            messaging.convertAndSend(topic(message.siteId()), message);
        } catch (RuntimeException e) {
            // Clients also poll, so a lost broadcast only delays the update.
            log.warn("Queue broadcast failed for site {}: {}", message.siteId(), e.getMessage());
        }
    }

    public static String topic(UUID siteId) {
        return "/topic/sites/" + siteId + "/queue";
    }
}
