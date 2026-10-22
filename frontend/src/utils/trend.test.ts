import { describe, expect, it } from 'vitest';
import type { SummaryRow } from '../api/types';
import { compactNumber, daysBetween, fillDays, niceTicks } from './trend';

const row = (key: string, issued: number, avgWait: number | null): SummaryRow => ({
  key, label: key, issued, served: issued - 1, abandoned: 1, voided: 0, rerouted: 0,
  avgQueueMinutes: null, avgWaitMinutes: avgWait, avgProcessMinutes: null, feedbackCount: 0, avgRating: null,
});

describe('trend helpers', () => {
  it('lists every day inclusive, across month ends', () => {
    expect(daysBetween('2026-10-30', '2026-11-02')).toEqual(['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02']);
    expect(daysBetween('2026-11-02', '2026-11-01')).toEqual([]);
  });

  it('fills missing days with zero counts but null averages', () => {
    const points = fillDays([row('2026-10-20', 5, 7.5), row('2026-10-22', 3, null)], '2026-10-20', '2026-10-22');
    expect(points.map((p) => p.issued)).toEqual([5, 0, 3]);
    expect(points.map((p) => p.avgWaitMinutes)).toEqual([7.5, null, null]);
  });

  it('chooses clean ticks that cover the maximum', () => {
    expect(niceTicks(0)).toEqual([0, 1]);
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8]);
    expect(niceTicks(95)).toEqual([0, 50, 100]);
    const big = niceTicks(1234);
    expect(big[big.length - 1]).toBeGreaterThanOrEqual(1234);
  });

  it('formats compact tick labels', () => {
    expect(compactNumber(950)).toBe('950');
    expect(compactNumber(2000)).toBe('2K');
    expect(compactNumber(1500)).toBe('1.5K');
  });
});
