import { expect, test } from '@playwright/test';
import { Api, issueTicket } from '../support/api';
import { loadState } from '../support/state';
import { uiLogin } from '../support/ui';

/** Dashboard "Services availed" chart lists every service with its client count. */
test('the dashboard charts clients per service', async ({ page }) => {
  const state = loadState();
  const kiosk = await Api.device(state.devices.kioskKey);
  await issueTicket(kiosk, state.services.walkIn.id);
  await kiosk.dispose();

  await uiLogin(page, state.users.superadmin.username, state.users.superadmin.password);
  await page.goto('/dashboard');
  // A national user starts on the all-sites view: totals, services across sites and a table of sites.
  await expect(page.getByRole('button', { name: 'All sites (national)' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('heading', { name: 'Services availed' })).toBeVisible();
  const sites = page.getByRole('table', { name: 'Today by site' });
  await expect(sites).toContainText(state.siteName);
  // Drill into one site.
  await sites.getByRole('button', { name: new RegExp(state.siteName) }).click();
  await expect(page.getByRole('heading', { name: 'Waiting by service' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Services availed' })).toBeVisible();
  // Default view: pie of the share of clients per service, with a labelled legend.
  const pie = page.getByRole('figure', { name: /Share of clients by service/ });
  await expect(pie.getByLabel(new RegExp(`^${state.services.walkIn.name}: [1-9]\\d* clients, \\d+%`))).toBeVisible();
  // Hovering a slice shows its count, share and breakdown.
  await pie.locator('path.viz-slice, circle').first().hover();
  await expect(pie.getByRole('status')).toContainText(/clients · \d+%/);
  await expect(pie.getByRole('status')).toContainText(/served · \d+ in queue · \d+ abandoned/);

  // Bars: every offered service, including unused ones.
  await page.getByRole('button', { name: 'Bars', exact: true }).click();
  const bars = page.getByRole('figure', { name: /Clients per service/ });
  await expect(bars.getByLabel(new RegExp(`^${state.services.walkIn.name}: [1-9]\\d* clients`))).toBeVisible();
  await expect(bars.getByLabel(new RegExp(`^${state.services.pmrf.name.replace(/[()]/g, '\\$&')}: \\d+ clients`))).toBeVisible();

  await page.getByRole('button', { name: 'Last 30 days' }).click();
  await expect(page.getByRole('figure', { name: 'Clients per service — last 30 days' })).toBeVisible();
  await page.getByRole('button', { name: 'Table', exact: true }).click();
  await expect(page.getByRole('table', { name: 'Clients per service' })).toBeVisible();
});
