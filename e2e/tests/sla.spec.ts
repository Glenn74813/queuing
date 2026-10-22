import { expect, test } from '@playwright/test';
import { Api, issueTicket, resetQueue, type Ticket } from '../support/api';
import { loadState } from '../support/state';
import { todayInManila, uiLogin } from '../support/ui';

/** SLA targets per region, and agent performance measured against them in Reports. */
test('a regional SLA is set in the UI and agent performance is reported against it', async ({ page }) => {
  test.setTimeout(120_000);
  const state = loadState();
  const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
  await resetQueue(admin, state.siteId);

  // An agent serves one client today.
  const kiosk = await Api.device(state.devices.kioskKey);
  await issueTicket(kiosk, state.services.pmrf.id);
  await kiosk.dispose();
  const agent = await Api.login(state.users.agent.username, state.users.agent.password);
  await agent.post('/agent/session/start', { counterId: state.counters.c1.id });
  const called = await agent.post<Ticket>('/agent/call-next');
  await agent.post(`/tickets/${called.id}/complete`);
  await agent.post('/agent/session/end');
  await agent.dispose();
  await admin.dispose();

  // Set the region's own SLA through the SLA Targets page.
  await uiLogin(page, state.users.superadmin.username, state.users.superadmin.password);
  await page.goto('/admin/sla');
  await expect(page.getByRole('heading', { name: 'SLA Targets' })).toBeVisible();
  const regionRow = page.getByRole('row').filter({ hasText: 'E2E Test Region' });
  await regionRow.getByRole('button', { name: /Set own SLA|Edit/ }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Waiting target (min)').fill('25');
  await dialog.getByLabel('Processing target (min)').fill('12');
  await dialog.getByLabel('Compliance target (%)').fill('85');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(regionRow).toContainText('Own SLA');

  // Agent performance tab shows the agent scored against those targets.
  await page.goto('/reports');
  await page.getByLabel('From').fill(todayInManila());
  await page.getByLabel('To (inclusive)').fill(todayInManila());
  await page.getByRole('button', { name: 'Run report' }).click();
  await page.getByRole('tab', { name: 'Agent performance' }).click();
  const table = page.getByRole('table', { name: 'Agent performance against SLA' });
  const agentRow = table.getByRole('row').filter({ hasText: state.users.agent.username });
  await expect(agentRow).toBeVisible();
  await expect(agentRow).toContainText('target 12 min');
  await expect(agentRow).toContainText('needs 85%');
  await expect(agentRow.getByText(/^(MET|NOT MET)$/)).toBeVisible();

  const excel = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download Excel (all agents)' }).click();
  expect((await excel).suggestedFilename()).toMatch(/^qms-agent-performance-.*\.xlsx$/);

  const download = page.waitForEvent('download');
  await page.getByRole('group', { name: 'Download agent performance' }).getByRole('button', { name: 'Download PDF' }).click();
  expect((await download).suggestedFilename()).toMatch(/^qms-agent-performance-.*\.pdf$/);
});
