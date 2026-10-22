import { expect, test } from '@playwright/test';
import { Api, issueTicket, resetQueue, type Ticket } from '../support/api';
import { loadState } from '../support/state';
import { useDeviceKey } from '../support/ui';

/**
 * The queue display reads each call aloud: "Ticket number A, 0, 0, 1, please proceed to Counter 1."
 * speechSynthesis is replaced by a recorder so the spoken text can be asserted without audio output.
 */
test.describe('display voice announcement', () => {
  const state = loadState();

  test.beforeEach(async () => {
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    await resetQueue(admin, state.siteId);
    await admin.dispose();
  });

  test('a called ticket is announced with its number and counter, and again on recall', async ({ page }) => {
    test.setTimeout(90_000);
    await page.addInitScript(() => {
      const spoken: string[] = [];
      (window as unknown as { __spoken: string[] }).__spoken = spoken;
      window.speechSynthesis.speak = (u: SpeechSynthesisUtterance) => {
        if (u.text.trim()) spoken.push(u.text);
      };
    });
    await useDeviceKey(page, state.devices.displayKey);
    await page.goto('/display');
    await expect(page.getByRole('heading', { name: 'Now serving' })).toBeVisible();
    // Voice is on by default.
    await expect(page.getByRole('button', { name: 'Voice on' })).toBeVisible();
    // If the browser blocks audio until a gesture, the display says so; one tap unlocks it.
    const unlockBar = page.getByText('Tap or click anywhere on this screen to turn on the call announcements');
    if (await unlockBar.isVisible()) {
      await page.mouse.click(400, 400);
      await expect(unlockBar).toHaveCount(0);
    }

    const kiosk = await Api.device(state.devices.kioskKey);
    const issued: Ticket = await issueTicket(kiosk, state.services.pmrf.id);
    await kiosk.dispose();
    const agent = await Api.login(state.users.agent.username, state.users.agent.password);
    await agent.post('/agent/session/start', { counterId: state.counters.c1.id });
    const called = await agent.post<Ticket>('/agent/call-next');
    expect(called.displayNumber).toBe(issued.displayNumber);

    const spokenDigits = issued.displayNumber.replace(/[^A-Za-z0-9]/g, '').split('').join(', ');
    const expected = `Ticket number ${spokenDigits}, please proceed to ${state.counters.c1.name}.`;
    const spoken = () => page.evaluate(() => (window as unknown as { __spoken: string[] }).__spoken);
    await expect.poll(spoken, { timeout: 25_000 }).toContain(expected);

    // A recall is announced again.
    await agent.post(`/tickets/${called.id}/recall`);
    await expect.poll(async () => (await spoken()).filter((t) => t === expected).length, { timeout: 25_000 }).toBe(2);
    await agent.post(`/tickets/${called.id}/complete`);
    await agent.post('/agent/session/end');
    await agent.dispose();
  });
});
