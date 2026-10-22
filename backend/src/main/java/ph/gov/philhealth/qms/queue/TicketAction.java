package ph.gov.philhealth.qms.queue;

/** Operations that change a ticket's status. Each is recorded as a ticket event. */
public enum TicketAction {
    ISSUE, CALL, RECALL, COMPLETE, HOLD, RESUME, TRANSFER, REMOVE, VOID, EXPIRE
}
