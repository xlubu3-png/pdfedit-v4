import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scannedPdf, simplePdf } from './fixtures'

const LINES = ['스캔 문서 글자 인식 시험', '연락처 010-2345-6789']

/** A scan made in the browser: the lines drawn on a 200 dpi A4 canvas in Malgun Gothic, saved as JPEG. */
async function scan(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate((lines) => {
    const canvas = document.createElement('canvas')
    canvas.width = 1654
    canvas.height = 2339
    const g = canvas.getContext('2d')!
    g.fillStyle = '#fff'
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.fillStyle = '#000'
    g.font = '40px "Malgun Gothic"'
    lines.forEach((line, i) => g.fillText(line, 200, 300 + 160 * i))
    return canvas.toDataURL('image/jpeg', 0.9)
  }, LINES)
  return scannedPdf(Buffer.from(dataUrl.split(',')[1], 'base64'), 1654, 2339)
}

/** The text a reader finds in a PDF, as this app's own reader sees it. */
async function textOf(page: Page, pdf: Buffer): Promise<string> {
  const response = await page.request.post('/api/v1/pdf/documents', {
    multipart: { file: { name: 'check.pdf', mimeType: 'application/pdf', buffer: pdf } },
  })
  const { documentId } = await response.json()
  const runs = (await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/text-runs`)).json()).runs
  return runs.map((r: { text: string }) => r.text).join('').replace(/\s+/g, '')
}

test('a scanned PDF is offered text recognition, and its export can then be searched', async ({ page }) => {
  await page.goto('/')
  const pdf = await scan(page)
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: pdf })

  await expect(page.getByText('스캔한 문서로 보입니다: scan.pdf')).toBeVisible()
  await page.getByRole('button', { name: '글자 인식', exact: true }).click()

  await expect(page.locator('.info-banner[role=status]').last()).toContainText('1쪽에서 글자 2줄을 인식했습니다', {
    timeout: 60_000,
  })
  await expect(page.locator('.page-card .page-ocr')).toHaveText('OCR')

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'PDF로 내보내기' }).click(),
  ])
  const text = await textOf(page, readFileSync(await download.path()))
  expect(text).toContain('스캔문서글자인식시험')
  expect(text).toContain('010-2345-6789')
})

test('text recognition from the menu says when a page already has text', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'typed.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Real text line']),
  })
  await expect(page.getByText('1페이지')).toBeVisible()
  await expect(page.getByText('스캔한 문서로 보입니다')).toBeHidden()

  await page.getByRole('button', { name: '글자 인식(OCR)' }).click()

  await expect(page.locator('.info-banner[role=status]').last()).toContainText('이미 글자가 들어 있는 PDF입니다', {
    timeout: 60_000,
  })
  await expect(page.locator('.page-card .page-ocr')).toHaveCount(0)
})
