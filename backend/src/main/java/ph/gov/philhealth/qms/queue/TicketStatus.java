package ph.gov.philhealth.qms.queue;

public enum TicketStatus {
    WAITING, CALLED, ON_HOLD, COMPLETED, REMOVED, VOIDED, EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REMOVED || this == VOIDED || this == EXPIRED;
    }
}
