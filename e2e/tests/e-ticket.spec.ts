import { expect, test } from '@playwright/test';
import { Api, resetQueue } from '../support/api';
import { loadState } from '../support/state';
import { useDeviceKey } from '../support/ui';

/**
 * Phone e-ticket and feedback: the kiosk shows a QR code whose link opens a public phone page that
 * follows the ticket through call and completion and then takes one rating; afterwards the kiosk's
 * "Rate our service" no longer finds the ticket.
 */
test.describe('phone e-ticket and feedback', () => {
  const state = loadState();

  test.beforeEach(async () => {
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    await resetQueue(admin, state.siteId);
    await admin.dispose();
  });

  test('QR link follows the ticket and takes feedback once', async ({ browser }) => {
    test.setTimeout(120_000);
    const kioskCtx = await browser.newContext();
    const kiosk = await kioskCtx.newPage();
    await useDeviceKey(kiosk, state.devices.kioskKey);
    await kiosk.goto('/kiosk');
    await kiosk.getByRole('button', { name: /PMRF/ }).click();
    if (await kiosk.getByRole('heading', { name: 'Do you qualify for priority lane?' }).isVisible()) {
      await kiosk.getByRole('button', { name: /^Regular/ }).click();
    }
    const issued = kiosk.waitForResponse((r) => r.url().endsWith('/kiosk/tickets') && r.request().method() === 'POST');
    await kiosk.getByRole('button', { name: /Show on screen only/ }).click();
    const ticket = (await (await issued).json()) as { id: string; displayNumber: string; publicToken: string };
    await expect(kiosk.getByRole('img', { name: new RegExp(`QR code linking to ticket ${ticket.displayNumber}`) })).toBeVisible();
    await expect(kiosk.getByText('Scan with your phone')).toBeVisible();

    /* ---- Phone ---- */
    const phoneCtx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true });
    const phone = await phoneCtx.newPage();
    await phone.goto(`/t/${ticket.publicToken}`);
    await expect(phone.getByText(ticket.displayNumber, { exact: true })).toBeVisible();
    await expect(phone.getByText('You are next in line.')).toBeVisible();

    const agent = await Api.login(state.users.agent.username, state.users.agent.password);
    await agent.post('/agent/session/start', { counterId: state.counters.c1.id });
    await agent.post('/agent/call-next');
    await phone.reload();
    await expect(phone.getByRole('alert')).toContainText(`Please proceed to ${state.counters.c1.name}`);

    await agent.post(`/tickets/${ticket.id}/complete`);
    await phone.reload();
    await expect(phone.getByText('Your transaction is complete.', { exact: false })).toBeVisible();
    await phone.getByText('Excellent').click();
    await phone.getByLabel(/Comments/).fill('Quick and friendly');
    await phone.getByRole('button', { name: 'Submit feedback' }).click();
    await expect(phone.getByRole('status').filter({ hasText: 'Thank you for your feedback!' })).toBeVisible();
    await phone.reload();
    await expect(phone.getByRole('button', { name: 'Submit feedback' })).toHaveCount(0);

    /* ---- Kiosk: already rated, so "Rate our service" does not find it ---- */
    await kiosk.getByRole('button', { name: 'Done' }).click();
    await kiosk.getByRole('button', { name: /Rate our service/ }).click();
    for (const ch of ticket.displayNumber.replace('-', '')) {
      await kiosk.getByRole('button', { name: ch, exact: true }).click();
    }
    await kiosk.getByRole('button', { name: 'Continue' }).click();
    await expect(kiosk.getByRole('alert')).toContainText('already been rated');

    await agent.post('/agent/session/end');
    await agent.dispose();
    await Promise.all([kioskCtx.close(), phoneCtx.close()]);
  });
});
