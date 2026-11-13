import { request, type FullConfig } from '@playwright/test'
import { simplePdf } from './fixtures'

/**
 * The app's first document on a fresh PC: PDFBox lists the PC's fonts and the screen's first pieces load, which can
 * take far longer than a test waits for one step. Done once here, so the first test is timed like the others; how long
 * it took is written to the log.
 */
export default async function warmUp(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL!
  const api = await request.newContext({ baseURL, timeout: 300_000 })
  const started = Date.now()
  try {
    const opened = await api.post('/api/v1/pdf/documents', {
      multipart: { file: { name: 'warmup.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Warm up 2026', 'Total 240,070'], 2) } },
    })
    const { documentId } = await opened.json()
    await api.get(`/api/v1/pdf/documents/${documentId}/pages/0/thumbnail`)
    await api.get(`/api/v1/pdf/documents/${documentId}/pages/0/text-runs`)
    await api.get('/')
    console.log(`앱 준비: ${Date.now() - started} ms`)
  } finally {
    await api.dispose()
  }
}
