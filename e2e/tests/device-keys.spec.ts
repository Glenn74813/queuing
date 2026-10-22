import { expect, test, type Page } from '@playwright/test';
import { Api } from '../support/api';
import { loadState } from '../support/state';

/**
 * Device key handling (regression for "display stuck on Reconnecting"): a kiosk and a display set up
 * in the same browser keep separate keys, and a key the server rejects (rotated) sends the page back
 * to its setup screen instead of retrying forever.
 */
test.describe('device keys', () => {
  const state = loadState();

  interface DeviceWithKey { device: { id: string }; apiKey: string }

  async function newDevice(admin: Api, type: 'KIOSK' | 'DISPLAY'): Promise<DeviceWithKey> {
    const code = `${type}-E2E-${Date.now().toString(36)}`.slice(0, 30);
    return admin.post<DeviceWithKey>(`/sites/${state.siteId}/devices`, { code, name: `E2E ${type}`, type });
  }

  async function enterKey(page: Page, key: string): Promise<void> {
    await page.getByLabel('Device API key').fill(key);
    await page.getByRole('button', { name: 'Save and start' }).click();
  }

  test('kiosk and display run side by side in one browser', async ({ page }) => {
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    const kiosk = await newDevice(admin, 'KIOSK');
    const display = await newDevice(admin, 'DISPLAY');
    await admin.dispose();

    await page.goto('/kiosk');
    await expect(page.getByRole('heading', { name: 'Kiosk setup' })).toBeVisible();
    await enterKey(page, kiosk.apiKey);
    await expect(page.getByRole('heading', { name: 'Please select a service' })).toBeVisible();

    // Same browser: the display asks for its own key rather than reusing the kiosk's.
    await page.goto('/display');
    await expect(page.getByRole('heading', { name: 'Queue display setup' })).toBeVisible();
    await enterKey(page, display.apiKey);
    await expect(page.getByRole('heading', { name: 'Now serving' })).toBeVisible();
    await expect(page.getByText(state.siteName)).toBeVisible();
    await expect(page.getByText('Reconnecting to the queue system')).toHaveCount(0);

    // And the kiosk still works without being set up again.
    await page.goto('/kiosk');
    await expect(page.getByRole('heading', { name: 'Please select a service' })).toBeVisible();
  });

  test('a rotated display key returns the display to setup with an explanation', async ({ page }) => {
    test.setTimeout(90_000);
    const admin = await Api.login(state.users.superadmin.username, state.users.superadmin.password);
    const display = await newDevice(admin, 'DISPLAY');

    await page.goto('/display');
    await enterKey(page, display.apiKey);
    await expect(page.getByText(state.siteName)).toBeVisible();

    const rotated = await admin.post<DeviceWithKey>(`/sites/${state.siteId}/devices/${display.device.id}/rotate-key`);
    await admin.dispose();

    // The next refresh (WebSocket event or 15 s poll) is rejected → setup screen, not "Reconnecting".
    await expect(page.getByRole('heading', { name: 'Queue display setup' })).toBeVisible({ timeout: 40_000 });
    await expect(page.getByText(/no longer accepted/)).toBeVisible();

    await enterKey(page, rotated.apiKey);
    await expect(page.getByText(state.siteName)).toBeVisible();
  });
});
