import { expect, test } from '@playwright/test';
import { loadState } from '../support/state';
import { sidebar, uiLogin } from '../support/ui';

test.describe('role-based access', () => {
  const state = loadState();

  test('agent sees only queue pages and is refused administration pages', async ({ page }) => {
    await uiLogin(page, state.users.agent.username, state.users.agent.password);

    const nav = sidebar(page);
    await expect(nav.getByRole('link', { name: 'Agent Console' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Queue Monitor' })).toBeVisible();
    await expect(nav.getByText('Administration', { exact: true })).toHaveCount(0);
    for (const label of ['Users', 'Regions & Sites', 'Counters', 'Devices', 'Reports', 'Audit Log', 'System & Sync']) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveCount(0);
    }

    await page.goto('/admin/users');
    const denied = page.getByRole('alert').filter({ hasText: 'Not authorized' });
    await expect(denied.getByRole('heading', { name: 'Not authorized' })).toBeVisible();
    await expect(denied).toContainText('You do not have permission to view this page.');
    await expect(page.getByRole('heading', { name: 'Users', exact: true })).toHaveCount(0);
  });

  test('superadmin sees the administration links (control)', async ({ page }) => {
    await uiLogin(page, state.users.superadmin.username, state.users.superadmin.password);
    const nav = sidebar(page);
    await expect(nav.getByRole('link', { name: 'Users', exact: true })).toBeVisible();
    await nav.getByRole('link', { name: 'Users', exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.getByText('Not authorized')).toHaveCount(0);
  });
});
