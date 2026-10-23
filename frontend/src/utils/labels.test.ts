import { entityLabel, humanizeCode, toCode } from './labels';

describe('labels', () => {
  it('turns codes into sentence-case words, keeping acronyms', () => {
    expect(humanizeCode('AGENT_SESSION_STARTED')).toBe('Agent session started');
    expect(humanizeCode('SLA_POLICY_SAVED')).toBe('SLA policy saved');
    expect(humanizeCode('LOGIN_SUCCEEDED')).toBe('Login succeeded');
    expect(humanizeCode(undefined)).toBe('—');
  });
  it('turns typed text back into the stored code', () => {
    expect(toCode(' Ticket voided ')).toBe('TICKET_VOIDED');
    expect(toCode('ticket-voided')).toBe('TICKET_VOIDED');
    expect(toCode('TICKET_VOIDED')).toBe('TICKET_VOIDED');
  });
  it('names entity types for people', () => {
    expect(entityLabel('ServiceCounter')).toBe('Service counter');
    expect(entityLabel('AppUser')).toBe('User');
    expect(entityLabel(null)).toBe('—');
  });
});
