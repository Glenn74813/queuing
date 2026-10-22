import { expect, test } from '@playwright/test';
import { Api, issueTicket } from '../support/api';
import { loadState } from '../support/state';
import { sidebar, todayInManila, uiLogin } from '../support/ui';

test.describe('reports', () => {
  const state = loadState();
  const ISSUED_HERE = 2;

  test.beforeAll(async () => {
    // Make sure today's report has data of its own, whatever ran before.
    const kiosk = await Api.device(state.devices.kioskKey);
    await issueTicket(kiosk, state.services.pmrf.id);
    await issueTicket(kiosk, state.services.walkIn.id, { channel: 'PRINTED' });
    await kiosk.dispose();
  });

  test('summary for today includes the issued tickets; trends offer a chart or table', async ({ page }) => {
    const today = todayInManila();
    await uiLogin(page, state.users.superadmin.username, state.users.superadmin.password);
    await sidebar(page).getByRole('link', { name: 'Reports', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Reports' })).toBeVisible();
    await expect(page.getByText('Choose filters and select “Run report”.')).toBeVisible();

    const filters = page.getByRole('form', { name: 'Report filters' });
    await filters.getByLabel('From').fill(today);
    await filters.getByLabel('To (inclusive)').fill(today);
    await filters.getByRole('button', { name: 'Run report' }).click();

    // Expected issued count from the API, so the UI is checked against the backend's own number.
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    const summary = await admin.get<{ totals: { issued: number } }>('/reports/summary', { from: today, to: today, groupBy: 'SITE' });
    await admin.dispose();
    expect(summary.totals.issued).toBeGreaterThanOrEqual(ISSUED_HERE);

    const table = page.getByRole('table', { name: 'Summary report' });
    await expect(table).toBeVisible();
    await expect(page.getByText(`${today} to ${today}`)).toBeVisible();
    const siteRow = table.getByRole('row').filter({ hasText: state.siteName });
    await expect(siteRow).toBeVisible();
    const totalRow = table.getByRole('row').filter({ has: page.getByRole('cell', { name: 'Total', exact: true }) });
    const issuedCell = totalRow.getByRole('cell').nth(1); // columns: Group, Issued, Served, ...
    await expect
      .poll(async () => Number((await issuedCell.innerText()).replace(/[^\d]/g, '')))
      .toBeGreaterThanOrEqual(ISSUED_HERE);
    expect(Number((await issuedCell.innerText()).replace(/[^\d]/g, ''))).toBe(summary.totals.issued);

    // Trends: one day selected → "not enough days" message (or a chart if the range has 2+ days).
    await page.getByRole('tab', { name: 'Trends' }).click();
    await expect(page.getByRole('tab', { name: 'Trends' })).toHaveAttribute('aria-selected', 'true');
    const chart = page.getByRole('figure', { name: 'Clients per day' });
    const notEnough = page.getByText('Choose a date range of at least two days to see a trend.');
    await expect(chart.or(notEnough)).toBeVisible();

    await page.getByLabel('Show as table').check();
    const trendTable = page.getByRole('table', { name: 'Daily trend' });
    await expect(trendTable).toBeVisible();
    await expect(trendTable.getByRole('row').filter({ hasText: `(${today})` })).toBeVisible();
    await expect(chart).toHaveCount(0);

    // A wider range draws the chart.
    await page.getByLabel('Show as table').uncheck();
    const weekAgo = new Date(Date.parse(`${today}T00:00:00Z`) - 6 * 86_400_000).toISOString().slice(0, 10);
    await filters.getByLabel('From').fill(weekAgo);
    await filters.getByRole('button', { name: 'Run report' }).click();
    await expect(chart).toBeVisible();
    await expect(notEnough).toHaveCount(0);
  });

  test('scheduled report: create, run now and download the generated file', async ({ page }) => {
    const name = `E2E daily ${Date.now().toString(36)}`;
    await uiLogin(page, state.users.superadmin.username, state.users.superadmin.password);
    await sidebar(page).getByRole('link', { name: 'Scheduled Reports' }).click();
    await expect(page.getByRole('heading', { name: 'Scheduled Reports' })).toBeVisible();

    await page.getByRole('button', { name: 'New schedule' }).click();
    const form = page.getByRole('dialog', { name: 'New scheduled report' });
    await form.getByLabel('Name').fill(name);
    await form.getByLabel('Frequency').selectOption('DAILY');
    await form.getByLabel('Format').selectOption('CSV');
    await form.getByLabel('Scope').selectOption('site');
    await form.getByLabel('Site', { exact: true }).selectOption({ label: `${state.siteCode} — ${state.siteName}` });
    await form.getByRole('button', { name: 'Save' }).click();
    await expect(form).toBeHidden();

    const schedules = page.getByRole('table', { name: 'Report schedules' });
    const row = schedules.getByRole('row').filter({ hasText: name });
    await expect(row).toBeVisible();
    await expect(row).toContainText('CSV');
    await expect(row).toContainText(state.siteName);

    await row.getByRole('button', { name: 'Run now' }).click();
    // "Run now" opens the generated-files dialog when done.
    const files = page.getByRole('dialog', { name: `Generated files — ${name}` });
    await expect(files).toBeVisible({ timeout: 30_000 });
    const runRow = files.getByRole('table', { name: 'Report runs' }).getByRole('row').filter({ hasText: 'SUCCESS' });
    await expect(runRow).toHaveCount(1);
    await expect(runRow).toContainText('Manual');

    const downloadPromise = page.waitForEvent('download');
    await runRow.getByRole('button', { name: 'Download' }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.csv$/i);

    await files.getByRole('button', { name: 'Close' }).click();
    await expect(files).toBeHidden();
    await expect(row).toContainText('SUCCESS');

    // Files dialog reopened from the list shows the same run.
    await row.getByRole('button', { name: 'Files' }).click();
    await expect(page.getByRole('dialog', { name: `Generated files — ${name}` }).getByText('SUCCESS')).toBeVisible();
  });
});
