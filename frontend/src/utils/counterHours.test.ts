import { counterSituation, formatHours, hoursError } from './counterHours';

const w = (dayOfWeek: number, openTime: string, closeTime: string) => ({ dayOfWeek, openTime, closeTime });

describe('formatHours', () => {
  it('groups consecutive days with the same windows', () => {
    const weekday = [1, 2, 3, 4, 5].flatMap((d) => [w(d, '13:00:00', '17:00:00'), w(d, '08:00:00', '12:00:00')]);
    expect(formatHours([...weekday, w(6, '08:00', '12:00')]))
      .toBe('Mon–Fri 08:00–12:00, 13:00–17:00 · Sat 08:00–12:00');
  });
  it('keeps non-consecutive days apart and says when there is no schedule', () => {
    expect(formatHours([w(1, '08:00', '17:00'), w(3, '08:00', '17:00')])).toBe('Mon 08:00–17:00 · Wed 08:00–17:00');
    expect(formatHours([])).toBe('Always (no schedule)');
  });
});

describe('hoursError', () => {
  it('matches the server rules', () => {
    expect(hoursError([w(1, '08:00', '12:00'), w(1, '13:00', '17:00')])).toBeNull();
    expect(hoursError([w(1, '12:00', '08:00')])).toBe('Mon: closing must be after opening.');
    expect(hoursError([w(2, '08:00', '12:00'), w(2, '11:00', '15:00')])).toBe('Tue: opening windows overlap.');
    expect(hoursError([w(7, '', '10:00')])).toBe('Sun: enter both opening and closing time.');
  });
});

describe('counterSituation', () => {
  const agent = { id: 'a', fullName: 'A' };
  it('flags unstaffed open counters and staffed closed ones', () => {
    expect(counterSituation({ active: true, scheduleState: 'OPEN' }).label).toBe('Scheduled open, no agent');
    expect(counterSituation({ active: true, scheduleState: 'CLOSED', currentAgent: agent }).label).toBe('Staffed outside hours');
    expect(counterSituation({ active: true, scheduleState: 'CLOSED' }).label).toBe('Closed (schedule)');
    expect(counterSituation({ active: true, scheduleState: 'NO_SCHEDULE', currentAgent: agent }).label).toBe('Open');
    expect(counterSituation({ active: false, scheduleState: 'OPEN' }).label).toBe('Inactive');
  });
});
