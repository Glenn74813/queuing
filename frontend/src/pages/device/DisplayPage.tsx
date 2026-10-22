import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { displayApi } from '../../api/device';
import { getDeviceKey } from '../../api/deviceClient';
import type { CallEntry, DisplayBoard } from '../../api/types';
import { AgencyBrand } from '../../components/AgencyBrand';
import { useClock } from '../../hooks/useClock';
import { useQueueTopic } from '../../hooks/useQueueTopic';
import { SPEECH_DELAY_MS, announcementText, audioBlocked, playChime, speak, unlockAudio } from '../../utils/announce';
import { formatTime } from '../../utils/format';
import { ContentRotator } from './ContentRotator';
import { DeviceShell, isContentLive } from './DeviceShell';

/** Reload the whole page if no fetch has succeeded for this long (recovers from wedged browsers/proxies). */
const HARD_RELOAD_AFTER_MS = 5 * 60_000;
const FLASH_MS = 6_000;
const SPEECH_KEY = 'qms.display.speech';

/** Identity of a call event; a recall of the same ticket counts as a new call. */
const callKey = (c: CallEntry) => `${c.ticketId}:${c.recallCount}`;

/**
 * Returns entries in `next` that were not present in `prev` (new calls or recalls).
 * On the first load (prev === null) nothing is considered new, so a restart does not re-announce.
 */
export function newCalls(prev: Set<string> | null, next: CallEntry[]): CallEntry[] {
  if (!prev) return [];
  return next.filter((c) => !prev.has(callKey(c)));
}

/** Voice announcements are on unless someone turned them off on this display. */
export function readSpeechPref(): boolean {
  try {
    return localStorage.getItem(SPEECH_KEY) !== '0';
  } catch {
    return true;
  }
}

function DisplayBoardView() {
  const [board, setBoard] = useState<DisplayBoard | null>(null);
  const [stale, setStale] = useState(false);
  const [flashing, setFlashing] = useState<Set<string>>(new Set());
  const [announcement, setAnnouncement] = useState('');
  const [speechOn, setSpeechOn] = useState(readSpeechPref);
  const [soundBlocked, setSoundBlocked] = useState(audioBlocked);
  const seen = useRef<Set<string> | null>(null);
  const lastSuccess = useRef(Date.now());
  const speechRef = useRef(speechOn);
  speechRef.current = speechOn;
  const now = useClock(1000);

  /** Chime + optional speech + visual flash for newly called tickets. */
  const announce = useCallback((calls: CallEntry[]) => {
    if (calls.length === 0) return;
    playChime();
    const texts = calls.map((c) => announcementText(c.displayNumber, c.counterName));
    setAnnouncement(texts.join('. '));
    if (speechRef.current) {
      // Let the chime finish first, then read each call in order.
      window.setTimeout(() => texts.forEach((t) => speak(t)), SPEECH_DELAY_MS);
    }
    const ids = calls.map((c) => c.ticketId);
    setFlashing((prev) => new Set([...prev, ...ids]));
    window.setTimeout(() => {
      setFlashing((prev) => {
        const next = new Set(prev);
        ids.forEach((id) => next.delete(id));
        return next;
      });
    }, FLASH_MS);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const b = await displayApi.board();
      lastSuccess.current = Date.now();
      setStale(false);
      const all = [...b.nowServing, ...b.recentCalls];
      announce(newCalls(seen.current, b.nowServing));
      seen.current = new Set(all.map(callKey));
      setBoard(b);
    } catch {
      setStale(true);
    }
  }, [announce]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Last-resort recovery: full reload when nothing has worked for 5 minutes.
  useEffect(() => {
    const t = window.setInterval(() => {
      if (Date.now() - lastSuccess.current > HARD_RELOAD_AFTER_MS) window.location.reload();
    }, 30_000);
    return () => window.clearInterval(t);
  }, []);

  // Live updates (WebSocket) with 15 s polling fallback; every event triggers a board re-fetch.
  const onRefresh = useCallback(() => void refresh(), [refresh]);
  const deviceHeaders = useCallback((): Record<string, string> => {
    const key = getDeviceKey('display');
    return key ? { 'X-Device-Key': key } : {};
  }, []);
  const { connected } = useQueueTopic(board?.site.id, { refresh: onRefresh, connectHeaders: deviceHeaders });

  const content = useMemo(
    () => (board?.content ?? []).filter((c) => isContentLive(c, now)),
    // Re-evaluate the schedule once a minute rather than every clock tick.
    [board, Math.floor(now.getTime() / 60_000)],
  );

  // Until someone taps the screen, browsers keep the display muted: unlock on the first interaction.
  useEffect(() => {
    if (!soundBlocked) return;
    const unlock = () => {
      unlockAudio();
      setSoundBlocked(false);
    };
    window.addEventListener('pointerdown', unlock, { once: true });
    window.addEventListener('keydown', unlock, { once: true });
    return () => {
      window.removeEventListener('pointerdown', unlock);
      window.removeEventListener('keydown', unlock);
    };
  }, [soundBlocked]);

  const toggleSpeech = () => {
    const next = !speechOn;
    setSpeechOn(next);
    try {
      localStorage.setItem(SPEECH_KEY, next ? '1' : '0');
    } catch {
      /* ignore */
    }
    // The click is a user gesture: use it to unlock audio output for later chimes.
    playChime();
  };

  const fullscreen = () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void document.documentElement.requestFullscreen?.();
  };

  return (
    <div className="display d-flex flex-column">
      <header className="display-header d-flex justify-content-between align-items-center px-4 py-2">
        <AgencyBrand size="lg" />
        <div className="fs-2 fw-bold">{board?.site.name ?? 'Queue display'}</div>
        <div className="d-flex align-items-center gap-3">
          <div className="clock" aria-label="Current time">
            {now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
          </div>
          <div className="btn-group btn-group-sm">
            <button type="button" className="btn btn-outline-light" aria-pressed={speechOn} onClick={toggleSpeech}>
              Voice {speechOn ? 'on' : 'off'}
            </button>
            <button type="button" className="btn btn-outline-light" onClick={fullscreen}>Fullscreen</button>
          </div>
        </div>
      </header>

      {soundBlocked && (
        <div className="small px-4 py-1 bg-info text-dark" role="status">
          Tap or click anywhere on this screen to turn on the call announcements (chime and voice).
        </div>
      )}
      {(stale || !connected) && (
        <div className={`small px-4 py-1 ${stale ? 'bg-warning text-dark' : 'bg-secondary'}`} role="status">
          {stale ? 'Reconnecting to the queue system…' : 'Live updates paused — refreshing every 15 seconds.'}
        </div>
      )}

      {/* Screen-reader announcement of new calls. */}
      <div className="visually-hidden" aria-live="assertive" aria-atomic="true">{announcement}</div>

      <div className="flex-grow-1 row g-3 m-0 p-3">
        <section className="col-lg-7" aria-labelledby="now-serving">
          <h1 id="now-serving" className="h2 text-uppercase mb-3">Now serving</h1>
          <div className="row g-3">
            {(board?.nowServing ?? []).map((c) => (
              <div key={c.ticketId} className="col-sm-6 col-xl-4">
                <div className={`now-serving-card p-3 text-center ${flashing.has(c.ticketId) ? 'flash' : ''}`}>
                  <div className="number">{c.displayNumber}</div>
                  <div className="counter">{c.counterName}</div>
                  <div className="small text-white-50">{c.serviceName}</div>
                </div>
              </div>
            ))}
            {board && board.nowServing.length === 0 && <p className="fs-3 text-white-50">Please wait for your number to be called.</p>}
          </div>
          <div className="d-flex gap-4 mt-4">
            <section className="flex-grow-1" aria-labelledby="recent-h">
              <h2 id="recent-h" className="h4 text-uppercase">Recent calls</h2>
              <ul className="list-unstyled fs-4 mb-0">
                {(board?.recentCalls ?? []).slice(0, 8).map((c) => (
                  <li key={callKey(c)} className="d-flex justify-content-between border-bottom border-secondary py-1">
                    <strong>{c.displayNumber}</strong>
                    <span>{c.counterName}</span>
                    <span className="text-white-50">{formatTime(c.calledAt)}</span>
                  </li>
                ))}
              </ul>
            </section>
            <div className="text-center">
              <div className="h4 text-uppercase">Waiting</div>
              <div className="display-3 fw-bold">{board?.waitingCount ?? '—'}</div>
            </div>
          </div>
        </section>
        <section className="col-lg-5" aria-label="Information">
          <div className="content-area h-100">
            <ContentRotator items={content} empty={<div className="p-5 text-center"><AgencyBrand size="xl" /></div>} />
          </div>
        </section>
      </div>
    </div>
  );
}

export function DisplayPage() {
  return (
    <DeviceShell kind="Queue display" verify={() => displayApi.board()}>
      <DisplayBoardView />
    </DeviceShell>
  );
}
