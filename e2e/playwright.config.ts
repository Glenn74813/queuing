import { defineConfig, devices } from '@playwright/test';
import { BASE_URL } from './support/env';

const isCI = !!process.env.CI;
// Local runs use the installed Google Chrome; CI uses Playwright's bundled Chromium (E2E_BROWSER_CHANNEL unset/empty).
const channel = process.env.E2E_BROWSER_CHANNEL ?? (isCI ? '' : 'chrome');

export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    // Business dates are evaluated in the site's time zone; keep the browser's "today" aligned.
    timezoneId: 'Asia/Manila',
    locale: 'en-PH',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 }, ...(channel ? { channel } : {}) },
    },
  ],
});
