import { expect, test } from '@playwright/test';
import { Api } from '../support/api';
import { loadState } from '../support/state';
import { uiLogin } from '../support/ui';

/**
 * Sign-in, forced first-login password change, session restore on reload and sign-out.
 * Uses a fresh agent account per run so the "must change password" state is always present,
 * whatever ran before.
 */
test.describe('authentication', () => {
  const state = loadState();
  const suffix = Date.now().toString(36);
  const user = {
    username: `e2e.new.${suffix}`,
    fullName: `New Agent ${suffix}`,
    tempPassword: 'Temp-First-Login-1',
    newPassword: 'Brand-New-Secret-77',
  };

  test.beforeAll(async () => {
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    await admin.post('/users', {
      username: user.username,
      fullName: user.fullName,
      email: `${user.username}@e2e.example.test`,
      password: user.tempPassword,
      assignments: [{ roleCode: 'AGENT', siteId: state.siteId }],
    });
    await admin.dispose();
  });

  test('wrong password shows an error and stays on the sign-in page', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('Username').fill(user.username);
    await page.getByLabel('Password', { exact: true }).fill('Definitely-Wrong-1');
    await page.getByRole('button', { name: 'Sign in' }).click();

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('Invalid username or password');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Sign out' })).toHaveCount(0);
  });

  test('first login forces a password change; the session survives reload; sign out works', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Username').fill(user.username);
    await page.getByLabel('Password', { exact: true }).fill(user.tempPassword);
    await page.getByRole('button', { name: 'Sign in' }).click();

    // Forced change-password screen
    await expect(page).toHaveURL(/\/change-password$/);
    await expect(page.getByRole('heading', { name: 'Change password' })).toBeVisible();
    await expect(page.getByText('You must set a new password before continuing.')).toBeVisible();
    // Other pages are not reachable until the password is changed.
    await page.goto('/agent');
    await expect(page).toHaveURL(/\/change-password$/);

    await page.getByLabel('Current password').fill(user.tempPassword);
    await page.getByLabel('New password', { exact: true }).fill(user.newPassword);
    await page.getByLabel('Confirm new password').fill(user.newPassword);
    await page.getByRole('button', { name: 'Change password' }).click();

    // Lands in the application (first page the agent may see).
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
    await expect(page.getByText(`Signed in as ${user.fullName}`)).toBeVisible();
    await expect(page).not.toHaveURL(/\/(login|change-password)$/);
    const landedOn = new URL(page.url()).pathname;

    // Regression: changing the password revoked the refresh token, so a reload used to sign the user out.
    await page.reload();
    await expect(page.getByText(`Signed in as ${user.fullName}`)).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`${landedOn}$`));
    await expect(page.getByRole('button', { name: 'Sign in' })).toHaveCount(0);

    // Sign out
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    // ...and stays signed out after a reload.
    await page.reload();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

    // The new password works for a fresh sign-in.
    await uiLogin(page, user.username, user.newPassword);
    await expect(page).not.toHaveURL(/\/change-password$/);
  });
});
