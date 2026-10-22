import { allowedActions } from './agentActions';

const AGENT = ['QUEUE_SERVE'];
const SUPERVISOR = ['QUEUE_VIEW', 'QUEUE_SUPERVISE', 'TICKET_VOID'];

describe('allowedActions', () => {
  it('with no current ticket only Call next (and break/end session) are available', () => {
    const a = allowedActions(null, AGENT);
    expect(a.callNext).toBe(true);
    expect(a.recall || a.complete || a.hold || a.transfer || a.remove || a.void || a.resume).toBe(false);
    expect(a.startBreak).toBe(true);
    expect(a.endSession).toBe(true);
  });

  it('CALLED enables recall/complete/hold/transfer/remove but not call next or resume', () => {
    const a = allowedActions('CALLED', AGENT);
    expect(a).toMatchObject({ recall: true, complete: true, hold: true, transfer: true, remove: true, callNext: false, resume: false });
    expect(a.startBreak).toBe(false);
    expect(a.endSession).toBe(false);
  });

  it('ON_HOLD enables resume/transfer/remove only', () => {
    const a = allowedActions('ON_HOLD', AGENT);
    expect(a).toMatchObject({ resume: true, transfer: true, remove: true, recall: false, complete: false, hold: false });
  });

  it('WAITING can only be removed (or voided with TICKET_VOID)', () => {
    expect(allowedActions('WAITING', AGENT)).toMatchObject({ remove: true, transfer: false, complete: false, void: false });
    expect(allowedActions('WAITING', [...AGENT, 'TICKET_VOID']).void).toBe(true);
  });

  it('void requires TICKET_VOID and a non-terminal status', () => {
    expect(allowedActions('CALLED', AGENT).void).toBe(false);
    expect(allowedActions('CALLED', ['QUEUE_SERVE', 'TICKET_VOID']).void).toBe(true);
    for (const terminal of ['COMPLETED', 'REMOVED', 'VOIDED', 'EXPIRED'] as const) {
      const a = allowedActions(terminal, [...AGENT, ...SUPERVISOR]);
      expect(a.void || a.remove || a.transfer || a.complete || a.recall || a.hold || a.resume).toBe(false);
    }
  });

  it('terminal current ticket still allows calling the next one', () => {
    expect(allowedActions('COMPLETED', AGENT).callNext).toBe(true);
  });

  it('on break: cannot call next or resume, can end break', () => {
    const a = allowedActions(null, AGENT, { onBreak: true });
    expect(a).toMatchObject({ callNext: false, startBreak: false, endBreak: true });
    expect(allowedActions('ON_HOLD', AGENT, { onBreak: true }).resume).toBe(false);
  });

  it('without a session nothing agent-specific is enabled', () => {
    const a = allowedActions(null, AGENT, { hasSession: false });
    expect(a).toMatchObject({ callNext: false, startBreak: false, endBreak: false, endSession: false });
  });

  it('ending a session is blocked while tickets are held', () => {
    expect(allowedActions(null, AGENT, { heldCount: 2 }).endSession).toBe(false);
  });

  it('supervisor without QUEUE_SERVE can intervene but not serve', () => {
    const a = allowedActions('CALLED', SUPERVISOR, { hasSession: false });
    expect(a).toMatchObject({ transfer: true, remove: true, void: true, callNext: false, startBreak: false });
  });

  it('users with neither QUEUE_SERVE nor QUEUE_SUPERVISE get nothing', () => {
    const a = allowedActions('CALLED', ['QUEUE_VIEW']);
    expect(Object.values(a).some(Boolean)).toBe(false);
  });
});
