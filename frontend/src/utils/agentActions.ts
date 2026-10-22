import type { TicketStatus } from '../api/types';

export interface AgentActionContext {
  /** Agent has an open counter session. */
  hasSession?: boolean;
  /** Agent session is on break. */
  onBreak?: boolean;
  /** Number of tickets held at the agent's counter (ending a session is refused while > 0). */
  heldCount?: number;
}

export interface AllowedActions {
  callNext: boolean;
  recall: boolean;
  complete: boolean;
  hold: boolean;
  resume: boolean;
  transfer: boolean;
  remove: boolean;
  void: boolean;
  startBreak: boolean;
  endBreak: boolean;
  endSession: boolean;
}

const NON_TERMINAL: TicketStatus[] = ['WAITING', 'CALLED', 'ON_HOLD'];

/**
 * Pure rules deciding which agent-console / supervisor buttons are enabled, mirroring the
 * "Allowed from" column of the contract. The server remains authoritative; this only avoids
 * offering actions that would be rejected with INVALID_TRANSITION / FORBIDDEN.
 *
 * @param status status of the ticket in focus, or null when there is none
 * @param permissions the user's permission codes (me.permissions)
 * @param ctx agent session context; when omitted only ticket-level rules are evaluated
 */
export function allowedActions(
  status: TicketStatus | null | undefined,
  permissions: readonly string[],
  ctx: AgentActionContext = {},
): AllowedActions {
  const serve = permissions.includes('QUEUE_SERVE');
  const supervise = permissions.includes('QUEUE_SUPERVISE');
  const canAct = serve || supervise;
  const { hasSession = true, onBreak = false, heldCount = 0 } = ctx;
  const called = status === 'CALLED';
  const held = status === 'ON_HOLD';
  const nonTerminal = !!status && NON_TERMINAL.includes(status);
  // While on break an agent must not serve; supervisors acting on others' tickets are unaffected.
  const agentReady = serve && hasSession && !onBreak;

  return {
    callNext: agentReady && !called,
    recall: canAct && called,
    complete: canAct && called,
    hold: canAct && called,
    // Resume returns the ticket to CALLED at *the agent's* counter, so it needs a ready agent and a free counter.
    resume: agentReady && held,
    transfer: canAct && (called || held),
    remove: canAct && (status === 'WAITING' || called || held),
    void: permissions.includes('TICKET_VOID') && nonTerminal,
    startBreak: serve && hasSession && !onBreak && !called,
    endBreak: serve && hasSession && onBreak,
    endSession: serve && hasSession && !called && heldCount === 0,
  };
}
