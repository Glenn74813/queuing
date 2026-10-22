import { NO_DATA, formatCount, formatMinutes, formatRating, formatSeconds, formatServedRate } from './metrics';

describe('report metric formatting', () => {
  it('formatMinutes uses one decimal and a dash for null', () => {
    expect(formatMinutes(12.345)).toBe('12.3');
    expect(formatMinutes(0)).toBe('0.0');
    expect(formatMinutes(null)).toBe(NO_DATA);
    expect(formatMinutes(undefined)).toBe(NO_DATA);
  });

  it('formatSeconds renders m:ss and h:mm:ss', () => {
    expect(formatSeconds(0)).toBe('0:00');
    expect(formatSeconds(65)).toBe('1:05');
    expect(formatSeconds(3600 + 2 * 60 + 3)).toBe('1:02:03');
    expect(formatSeconds(59.6)).toBe('1:00');
    expect(formatSeconds(undefined)).toBe(NO_DATA);
    expect(formatSeconds(-1)).toBe(NO_DATA);
  });

  it('formatRating uses two decimals', () => {
    expect(formatRating(4.5)).toBe('4.50');
    expect(formatRating(null)).toBe(NO_DATA);
  });

  it('formatCount adds thousands separators', () => {
    expect(formatCount(1234567)).toBe('1,234,567');
    expect(formatCount(0)).toBe('0');
  });

  it('formatServedRate guards against division by zero', () => {
    expect(formatServedRate(45, 60)).toBe('75.0%');
    expect(formatServedRate(0, 0)).toBe(NO_DATA);
  });
});
