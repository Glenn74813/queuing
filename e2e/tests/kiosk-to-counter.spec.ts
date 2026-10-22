import { expect, test } from '@playwright/test';
import { Api, resetQueue } from '../support/api';
import { loadState } from '../support/state';
import { uiLogin, useDeviceKey } from '../support/ui';

/**
 * Core flow across three browser contexts: a client takes a priority number at the kiosk, the
 * agent calls and completes it, and the queue display follows along (WebSocket or 15 s polling).
 */
test.describe('kiosk to counter to display', () => {
  const state = loadState();

  test.beforeEach(async () => {
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    await resetQueue(admin, state.siteId);
    await admin.dispose();
  });

  test('ticket issued at the kiosk is called, shown on the display and completed', async ({ browser }) => {
    test.setTimeout(120_000);

    /* ---- Kiosk ---- */
    const kioskCtx = await browser.newContext();
    const kiosk = await kioskCtx.newPage();
    await useDeviceKey(kiosk, state.devices.kioskKey);
    await kiosk.goto('/kiosk');
    await expect(kiosk.getByRole('heading', { name: 'Please select a service' })).toBeVisible();
    await kiosk.getByRole('button', { name: /PMRF/ }).click();

    await expect(kiosk.getByRole('heading', { name: 'Do you qualify for priority lane?' })).toBeVisible();
    await kiosk.getByRole('button', { name: new RegExp(`^${state.priorities.senior.name}`) }).click();

    await expect(kiosk.getByText(`Priority: ${state.priorities.senior.name}`)).toBeVisible();
    await kiosk.getByRole('button', { name: /Show on screen only/ }).click();

    const result = kiosk.getByRole('region', { name: 'Your queue number is' });
    await expect(result).toBeVisible();
    await expect(result).toContainText(`Priority: ${state.priorities.senior.name}`);
    const numberText = (await result.locator('.kiosk-ticket-number').innerText()).trim();
    // e.g. "A-001" (regular) or "PA-001" (priority lane, see api-contract.md)
    expect(numberText).toMatch(new RegExp(`^P?${state.services.pmrf.prefix}-\\d{3}$`));
    await expect(result).toContainText('Please remember or photograph this number.');

    /* ---- Display (before the call) ---- */
    const displayCtx = await browser.newContext();
    const display = await displayCtx.newPage();
    await useDeviceKey(display, state.devices.displayKey);
    await display.goto('/display');
    const nowServing = display.getByRole('region', { name: 'Now serving' });
    await expect(nowServing).toBeVisible();
    const servingCard = nowServing.locator('.now-serving-card').filter({ hasText: numberText });
    await expect(servingCard).toHaveCount(0);

    /* ---- Agent ---- */
    const agentCtx = await browser.newContext();
    const agent = await agentCtx.newPage();
    await uiLogin(agent, state.users.agent.username, state.users.agent.password);
    await agent.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Agent Console' }).click();
    await expect(agent.getByRole('heading', { name: 'Start a counter session' })).toBeVisible();
    await agent.getByLabel('Counter', { exact: true }).selectOption({ label: `${state.counters.c1.name} (${state.counters.c1.code})` });
    await agent.getByRole('button', { name: 'Start session' }).click();

    await expect(agent.getByRole('button', { name: 'End session' })).toBeVisible();
    const current = agent.getByRole('region', { name: 'Current ticket' });
    await expect(current).toContainText('No ticket at your counter.');
    await agent.getByRole('button', { name: 'Call next' }).click();
    await expect(current).toContainText(numberText);
    await expect(current).toContainText('CALLED');
    await expect(current).toContainText(state.priorities.senior.name);

    /* ---- Display shows the call ---- */
    await expect(servingCard).toBeVisible({ timeout: 20_000 });
    await expect(servingCard).toContainText(state.counters.c1.name);

    /* ---- Agent completes ---- */
    await agent.getByRole('button', { name: 'Complete' }).click();
    await expect(agent.getByText(`Ticket ${numberText} completed.`)).toBeVisible();
    await expect(current).toContainText('No ticket at your counter.');

    /* ---- Display moves it to recent calls ---- */
    const recent = display.getByRole('region', { name: 'Recent calls' });
    await expect(servingCard).toHaveCount(0, { timeout: 20_000 });
    await expect(recent.getByRole('listitem').filter({ hasText: numberText })).toContainText(state.counters.c1.name, {
      timeout: 20_000,
    });

    await Promise.all([kioskCtx.close(), displayCtx.close(), agentCtx.close()]);
  });
});
