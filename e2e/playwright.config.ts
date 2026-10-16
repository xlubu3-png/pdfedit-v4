import { defineConfig } from '@playwright/test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/**
 * Starts the built app (the jar, which holds the screen too) on its own port with an empty data folder, and
 * drives it in the browser that is already on the PC: Microsoft Edge, or Chrome with E2E_BROWSER=chrome.
 * Build the jar first:  backend\gradlew.bat bootJar
 */
const port = Number(process.env.E2E_PORT ?? 19090)
const dataDir = mkdtempSync(join(tmpdir(), 'wintech-pdf-e2e-'))

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${port}`,
    channel: process.env.E2E_BROWSER === 'chrome' ? 'chrome' : 'msedge',
    viewport: { width: 1400, height: 900 },
    acceptDownloads: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: `java -jar ../backend/build/libs/pdfedit-v2-backend-0.1.0.jar --server.port=${port}`,
    url: `http://localhost:${port}/api/v1/pdf/health`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: { ...process.env, PDFEDIT_DATA_DIR: dataDir } as Record<string, string>,
  },
})
