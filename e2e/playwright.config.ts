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
// The Windows installer build tests the program as it is installed: its own Java runtime (E2E_JAVA, the one jlink
// made) and its unpacked jar (E2E_JAR). Elsewhere: the java on the PATH and the jar gradle built.
const java = process.env.E2E_JAVA ?? 'java'
const jar = process.env.E2E_JAR ?? '../backend/build/libs/pdfedit-v2-backend-0.1.0.jar'
const dataDir = mkdtempSync(join(tmpdir(), 'wintech-pdf-e2e-'))

export default defineConfig({
  testDir: './tests',
  globalSetup: './tests/warmup.setup.ts',
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
    command: `"${java}" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar "${jar}" --server.port=${port}`,
    url: `http://localhost:${port}/api/v1/pdf/health`,
    reuseExistingServer: false,
    timeout: 120_000,
    // The app's own log in the test output: when a test fails on the build PC, what the app did is there.
    stdout: 'pipe',
    // Scans are not recognized as they open unless a test switches it on: most tests open one and choose.
    // No browser window of its own either (on a PC with a desktop the app opens one as it starts).
    env: { ...process.env, PDFEDIT_DATA_DIR: dataDir, PDFEDIT_AUTO_OCR: 'false', PDFEDIT_NO_BROWSER: 'true' } as Record<string, string>,
  },
})
