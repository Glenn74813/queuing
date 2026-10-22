/**
 * Text read aloud on the queue display, e.g. "A-004" + "Counter 1" →
 * "Ticket number A, 0, 0, 4, please proceed to Counter 1." Characters are separated by commas so
 * text-to-speech reads each digit ("zero zero four", not "four") with a short pause between them.
 */
export function announcementText(displayNumber: string, counterName: string): string {
  const spoken = displayNumber
    .replace(/[^A-Za-z0-9]/g, '')
    .split('')
    .join(', ');
  return `Ticket number ${spoken}, please proceed to ${counterName}.`;
}

/** Delay between the chime and the voice, so the chime does not cut into the first word. */
export const SPEECH_DELAY_MS = 800;

let audioCtx: AudioContext | null = null;

/** Plays a short two-tone chime generated with the Web Audio API (no audio assets needed). */
export function playChime(): void {
  try {
    const Ctor =
      window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    audioCtx = audioCtx ?? new Ctor();
    const ctx = audioCtx;
    if (ctx.state === 'suspended') void ctx.resume();
    const tones: [number, number][] = [
      [880, 0],
      [660, 0.35],
    ];
    tones.forEach(([freq, offset]) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = freq;
      const t0 = ctx.currentTime + offset;
      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(0.4, t0 + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.6);
      osc.connect(gain).connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + 0.65);
    });
  } catch {
    /* audio unavailable (autoplay policy etc.) */
  }
}

/** Preferred voice: Philippine English, then any English voice, else the browser default. */
export function pickVoice(voices: readonly SpeechSynthesisVoice[]): SpeechSynthesisVoice | undefined {
  const lang = (v: SpeechSynthesisVoice) => v.lang.toLowerCase().replace('_', '-');
  return (
    voices.find((v) => lang(v) === 'en-ph') ??
    voices.find((v) => lang(v) === 'en-us' && v.localService) ??
    voices.find((v) => lang(v).startsWith('en'))
  );
}

/** Speaks the announcement via speechSynthesis when available. */
export function speak(text: string): void {
  try {
    if (!('speechSynthesis' in window)) return;
    const u = new SpeechSynthesisUtterance(text);
    const voice = pickVoice(window.speechSynthesis.getVoices());
    if (voice) u.voice = voice;
    u.lang = voice?.lang ?? 'en-PH';
    u.rate = 0.9;
    window.speechSynthesis.speak(u);
  } catch {
    /* ignore */
  }
}

/**
 * Browsers keep audio muted until the page has had a user interaction (unless started with
 * --autoplay-policy=no-user-gesture-required, as on a locked-down display PC). True while blocked.
 */
export function audioBlocked(): boolean {
  try {
    const Ctor =
      window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (Ctor) {
      audioCtx = audioCtx ?? new Ctor();
      return audioCtx.state !== 'running';
    }
    const activation = (navigator as Navigator & { userActivation?: { hasBeenActive: boolean } }).userActivation;
    return activation ? !activation.hasBeenActive : false;
  } catch {
    return false;
  }
}

/** Call from a user gesture: resumes audio output and primes speech so later announcements play. */
export function unlockAudio(): void {
  try {
    if (audioCtx?.state === 'suspended') void audioCtx.resume();
    if ('speechSynthesis' in window) {
      const primer = new SpeechSynthesisUtterance(' ');
      primer.volume = 0;
      window.speechSynthesis.speak(primer);
    }
  } catch {
    /* ignore */
  }
}
