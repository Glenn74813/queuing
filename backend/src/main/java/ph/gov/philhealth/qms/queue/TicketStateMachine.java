package ph.gov.philhealth.qms.queue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import ph.gov.philhealth.qms.common.ApiException;

/**
 * The single authority on which ticket actions are legal from which status.
 *
 * <pre>
 *   ISSUE ──► WAITING ──CALL──► CALLED ──COMPLETE──► COMPLETED
 *               ▲                │  ▲ RECALL (stays CALLED)
 *               │ TRANSFER       │  │
 *               └────────────────┤  └── RESUME ── ON_HOLD ◄── HOLD
 *   REMOVE: WAITING/CALLED/ON_HOLD ──► REMOVED   (e.g. client did not respond)
 *   VOID:   any non-terminal       ──► VOIDED    (issued in error)
 *   EXPIRE: any non-terminal at end of business day ──► EXPIRED
 * </pre>
 */
public final class TicketStateMachine {

    private static final Set<TicketStatus> OPEN = EnumSet.of(TicketStatus.WAITING, TicketStatus.CALLED,
            TicketStatus.ON_HOLD);

    private static final Map<TicketAction, Set<TicketStatus>> ALLOWED_FROM = Map.of(
            TicketAction.CALL, EnumSet.of(TicketStatus.WAITING),
            TicketAction.RECALL, EnumSet.of(TicketStatus.CALLED),
            TicketAction.COMPLETE, EnumSet.of(TicketStatus.CALLED),
            TicketAction.HOLD, EnumSet.of(TicketStatus.CALLED),
            TicketAction.RESUME, EnumSet.of(TicketStatus.ON_HOLD),
            TicketAction.TRANSFER, EnumSet.of(TicketStatus.CALLED, TicketStatus.ON_HOLD),
            TicketAction.REMOVE, OPEN,
            TicketAction.VOID, OPEN,
            TicketAction.EXPIRE, OPEN);

    private TicketStateMachine() {
    }

    public static boolean isAllowed(TicketAction action, TicketStatus from) {
        Set<TicketStatus> allowed = ALLOWED_FROM.get(action);
        return allowed != null && allowed.contains(from);
    }

    /** Status after the action; throws INVALID_TRANSITION when the action is not legal. */
    public static TicketStatus next(TicketAction action, TicketStatus from, String displayNumber) {
        if (!isAllowed(action, from)) {
            throw ApiException.invalidTransition("Ticket %s cannot be %s from status %s"
                    .formatted(displayNumber, pastTense(action), from));
        }
        return switch (action) {
            case CALL, RECALL, RESUME -> TicketStatus.CALLED;
            case COMPLETE -> TicketStatus.COMPLETED;
            case HOLD -> TicketStatus.ON_HOLD;
            case TRANSFER -> TicketStatus.WAITING;
            case REMOVE -> TicketStatus.REMOVED;
            case VOID -> TicketStatus.VOIDED;
            case EXPIRE -> TicketStatus.EXPIRED;
            case ISSUE -> TicketStatus.WAITING;
        };
    }

    private static String pastTense(TicketAction a) {
        return switch (a) {
            case CALL -> "called";
            case RECALL -> "recalled";
            case COMPLETE -> "completed";
            case HOLD -> "put on hold";
            case RESUME -> "resumed";
            case TRANSFER -> "transferred";
            case REMOVE -> "removed";
            case VOID -> "voided";
            case EXPIRE -> "expired";
            case ISSUE -> "issued";
        };
    }
}
