import { expect, type Page } from '@playwright/test';

/** Signs in through the login form and waits for the staff shell. */
export async function uiLogin(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

/** Stores a kiosk/display API key before any page script runs (same as the device set-up screen). */
export async function useDeviceKey(page: Page, apiKey: string): Promise<void> {
  await page.addInitScript((key) => window.localStorage.setItem('qms.deviceKey', key), apiKey);
}

/** The left sidebar. */
export function sidebar(page: Page) {
  return page.getByRole('navigation', { name: 'Main navigation' });
}

/** Today's business date (yyyy-MM-dd) in the site's time zone. */
export function todayInManila(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date());
}
