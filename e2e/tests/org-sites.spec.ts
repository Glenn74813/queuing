import { expect, test } from '@playwright/test';
import { loadState } from '../support/state';
import { uiLogin } from '../support/ui';

/**
 * Regression: a site created in Regions & Sites must appear in the header site selector right away
 * (the user's site list used to be cached from sign-in until the next login).
 */
test('a newly created site is immediately selectable', async ({ page }) => {
  const state = loadState();
  const code = `S${Date.now().toString(36).toUpperCase().slice(-6)}`;
  const name = `E2E Site ${code}`;

  await uiLogin(page, state.users.superadmin.username, state.users.superadmin.password);
  await page.goto('/admin/org');
  await page.getByRole('button', { name: 'New site' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Code').fill(code);
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByLabel('Region').selectOption(state.regionId);
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toHaveCount(0);

  // No reload: the header selector already offers the new site, and selecting it works.
  const selector = page.getByLabel('Site', { exact: true });
  await expect(selector.locator('option', { hasText: name })).toHaveCount(1);
  await selector.selectOption({ label: `${code} — ${name}` });
  await page.getByRole('link', { name: 'Dashboard' }).click();
  await expect(page.getByText(`${name} · business date`)).toBeVisible();
});
