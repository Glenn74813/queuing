import { brokerUrlFromLocation } from '../hooks/wsUrl';
import { newCalls } from '../pages/device/DisplayPage';
import { isContentLive } from '../pages/device/DeviceShell';
import { announcementText } from './announce';
import { sniffMime, validateUpload } from './contentUpload';
import { passwordPolicyError } from './password';
import type { CallEntry } from '../api/types';

describe('brokerUrlFromLocation', () => {
  it('derives ws/wss from the page protocol', () => {
    expect(brokerUrlFromLocation({ protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws');
    expect(brokerUrlFromLocation({ protocol: 'https:', host: 'qms.example.gov' })).toBe('wss://qms.example.gov/ws');
  });
});

describe('announcementText', () => {
  it('spells the ticket number for TTS', () => {
    expect(announcementText('A-004', 'Counter 1')).toBe('Ticket number A, 0, 0, 4, please proceed to Counter 1.');
    expect(announcementText('AB-120', 'Window 2')).toBe('Ticket number A, B, 1, 2, 0, please proceed to Window 2.');
  });
});

describe('content upload validation', () => {
  const file = (type: string, size: number) => ({ type, size });
  it('accepts allowed types within size limits', () => {
    expect(validateUpload(file('image/png', 1024), 'IMAGE')).toBeNull();
    expect(validateUpload(file('video/webm', 150 * 1024 * 1024), 'VIDEO')).toBeNull();
    expect(validateUpload(null, 'ANNOUNCEMENT')).toBeNull();
  });
  it('rejects wrong types, oversize files and missing files', () => {
    expect(validateUpload(file('image/gif', 10), 'IMAGE')).toMatch(/not allowed/);
    expect(validateUpload(file('image/png', 11 * 1024 * 1024), 'IMAGE')).toMatch(/too large/);
    expect(validateUpload(file('video/mp4', 201 * 1024 * 1024), 'VIDEO')).toMatch(/too large/);
    expect(validateUpload(null, 'IMAGE')).toMatch(/required/);
  });
  it('sniffs magic bytes', () => {
    expect(sniffMime(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]))).toBe('image/png');
    expect(sniffMime(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe('image/jpeg');
    expect(sniffMime(new Uint8Array([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70]))).toBe('video/mp4');
    expect(sniffMime(new Uint8Array([0x47, 0x49, 0x46, 0x38]))).toBeNull();
  });
});

describe('display newCalls', () => {
  const entry = (id: string, recall = 0): CallEntry => ({
    ticketId: id, displayNumber: id, counterName: 'C1', serviceName: 'S', calledAt: '2026-10-22T02:00:00Z', recallCount: recall,
  });
  it('announces nothing on first load, then new tickets and recalls', () => {
    expect(newCalls(null, [entry('a')])).toEqual([]);
    const seen = new Set(['a:0']);
    expect(newCalls(seen, [entry('a'), entry('b')]).map((c) => c.ticketId)).toEqual(['b']);
    expect(newCalls(seen, [entry('a', 1)]).map((c) => c.recallCount)).toEqual([1]);
  });
});

describe('isContentLive', () => {
  const now = new Date('2026-10-22T02:00:00Z');
  it('requires PUBLISHED status and an active window', () => {
    expect(isContentLive({ status: 'PUBLISHED' }, now)).toBe(true);
    expect(isContentLive({ status: 'DRAFT' }, now)).toBe(false);
    expect(isContentLive({ status: 'PUBLISHED', startsAt: '2026-10-23T00:00:00Z' }, now)).toBe(false);
    expect(isContentLive({ status: 'PUBLISHED', endsAt: '2026-10-21T00:00:00Z' }, now)).toBe(false);
  });
});

describe('passwordPolicyError', () => {
  it('enforces the server policy', () => {
    expect(passwordPolicyError('short')).toMatch(/12/);
    expect(passwordPolicyError('alllowercase123')).toMatch(/uppercase/);
    expect(passwordPolicyError('ALLUPPERCASE123')).toMatch(/lowercase/);
    expect(passwordPolicyError('NoDigitsHereAtAll')).toMatch(/digit/);
    expect(passwordPolicyError('GoodPassword123')).toBeNull();
  });
});

describe('display voice', () => {
  it('prefers Philippine English, then local US English, then any English voice', async () => {
    const { pickVoice } = await import('./announce');
    const v = (lang: string, localService = true) => ({ lang, localService, name: lang }) as SpeechSynthesisVoice;
    expect(pickVoice([v('fil-PH'), v('en-US'), v('en_PH')])?.lang).toBe('en_PH');
    expect(pickVoice([v('en-GB'), v('en-US', false), v('en-US')])).toEqual(v('en-US'));
    expect(pickVoice([v('ja-JP'), v('en-AU')])?.lang).toBe('en-AU');
    expect(pickVoice([v('ja-JP')])).toBeUndefined();
  });

  it('is on by default and remembers when turned off', async () => {
    const { readSpeechPref } = await import('../pages/device/DisplayPage');
    window.localStorage.removeItem('qms.display.speech');
    expect(readSpeechPref()).toBe(true);
    window.localStorage.setItem('qms.display.speech', '0');
    expect(readSpeechPref()).toBe(false);
    window.localStorage.removeItem('qms.display.speech');
  });
});
