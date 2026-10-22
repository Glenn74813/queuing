import { expect, test } from '@playwright/test';
import { Api, issueTicket, resetQueue } from '../support/api';
import { loadState } from '../support/state';
import { sidebar, uiLogin } from '../support/ui';

test.describe('supervisor queue monitor', () => {
  const state = loadState();
  const counter = state.counters.c2;
  let calledNumber = '';

  test.beforeEach(async () => {
    // Known state via the API: empty queue, the agent signed in at Counter 2 serving one ticket.
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    await resetQueue(admin, state.siteId);
    await admin.dispose();

    const kiosk = await Api.device(state.devices.kioskKey);
    await issueTicket(kiosk, state.services.walkIn.id);
    await kiosk.dispose();

    const agent = await Api.login(state.users.agent.username, state.users.agent.password);
    await agent.post('/agent/session/start', { counterId: counter.id });
    const called = await agent.post<{ displayNumber: string }>('/agent/call-next');
    calledNumber = called.displayNumber;
    await agent.dispose();
  });

  test('closing a staffed counter signs the agent out and returns the ticket to the queue', async ({ page }) => {
    await uiLogin(page, state.users.supervisor.username, state.users.supervisor.password);
    await sidebar(page).getByRole('link', { name: 'Queue Monitor' }).click();
    await expect(page.getByRole('heading', { name: 'Queue Monitor' })).toBeVisible();

    const counters = page.getByRole('table', { name: 'Counters and agents' });
    const row = counters.getByRole('row').filter({ hasText: `${counter.name} (${counter.code})` });
    await expect(row).toContainText(state.users.agent.fullName);
    await expect(row).toContainText(calledNumber);
    const served = page.getByRole('table', { name: 'Called tickets' });
    await expect(served).toContainText(calledNumber);

    await row.getByRole('button', { name: 'Close counter' }).click();
    const dialog = page.getByRole('dialog', { name: `Close ${counter.name}` });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(`Signs ${state.users.agent.fullName} out of this counter.`);
    const confirm = dialog.getByRole('button', { name: 'Close counter' });
    await expect(confirm).toBeDisabled(); // a reason is required
    await dialog.getByLabel('Reason').fill('Agent called away (E2E)');
    await confirm.click();
    await expect(dialog).toBeHidden();

    await expect(row).toContainText('Unstaffed');
    await expect(row).not.toContainText(state.users.agent.fullName);
    await expect(row.getByRole('button', { name: 'Close counter' })).toHaveCount(0);
    // The ticket being served goes back to the waiting line.
    await expect(served).not.toContainText(calledNumber);
    await expect(page.getByRole('table', { name: 'Waiting tickets' })).toContainText(calledNumber);

    // The agent really has no session any more.
    const agent = await Api.login(state.users.agent.username, state.users.agent.password);
    expect(await agent.get<unknown>('/agent/session')).toBeFalsy(); // 204 No Content
    await agent.dispose();
  });
});
