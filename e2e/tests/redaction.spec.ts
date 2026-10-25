import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { simplePdf } from './fixtures'

async function upload(page: Page, lines: string[], pages = 1): Promise<string> {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'personal.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(lines, pages),
  })
  const thumb = page.locator('img[src*="/thumbnail"]').first()
  await expect(thumb).toBeVisible()
  return /documents\/([^/]+)\//.exec((await thumb.getAttribute('src')) ?? '')![1]
}

/** The text of a PDF as the server reads it, after uploading it as a new document. */
async function textOf(page: Page, pdf: Buffer): Promise<string> {
  const response = await page.request.post('/api/v1/pdf/documents', {
    multipart: { file: { name: 'check.pdf', mimeType: 'application/pdf', buffer: pdf } },
  })
  const { documentId } = await response.json()
  const runs = (await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/text-runs`)).json()).runs
  return runs.map((r: { text: string }) => r.text).join(' ')
}

test('a phone number found on the page is redacted on export, and the file no longer holds it', async ({ page }) => {
  const documentId = await upload(page, ['Call 010-1234-5678 today', 'Second line'])

  await page.getByRole('button', { name: '개인정보 가리기' }).click()
  const dialog = page.getByRole('dialog', { name: '개인정보 찾아 가리기' })
  await dialog.getByRole('button', { name: '찾기', exact: true }).click()
  await expect(dialog).toContainText('010-1234-5678')
  await expect(dialog).toContainText('휴대전화번호')
  await dialog.getByRole('button', { name: '선택한 1곳 가리기' }).click()
  await expect(dialog).toContainText('1곳을 가림 영역으로 넣었습니다')

  const saved = await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/redactions`)).json()
  expect(saved).toHaveLength(1)
  await dialog.getByRole('button', { name: '닫기' }).click()

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'PDF로 내보내기' }).click(),
  ])
  await expect(page.locator('.info-banner')).toContainText('가림 영역 1곳을 적용했습니다')
  const text = await textOf(page, readFileSync(await download.path()))
  expect(text).toContain('Call')
  expect(text).toContain('today')
  expect(text).toContain('Second line')
  expect(text).not.toContain('1234')
})

test('an area drawn by hand is saved, shown, and removed again with its ✕', async ({ page }) => {
  const documentId = await upload(page, ['Hand drawn area here'])

  await page.getByRole('button', { name: '개인정보 가리기' }).click()
  await page.getByRole('button', { name: '직접 그려서 가리기…' }).click()
  const layer = page.getByTestId('redact-layer')
  await expect(layer).toBeVisible()
  const box = (await layer.boundingBox())!
  await page.mouse.move(box.x + 60, box.y + 100)
  await page.mouse.down()
  await page.mouse.move(box.x + 200, box.y + 130, { steps: 5 })
  await page.mouse.up()

  await expect(page.getByTestId('redact-box')).toHaveCount(1)
  await expect(page.locator('.te-status')).toContainText('가림 영역 1곳 · 저장됨')
  const saved = await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/redactions`)).json()
  expect(saved).toHaveLength(1)
  expect(saved[0].width).toBeGreaterThan(50)

  await page.getByTestId('redact-box').hover()
  await page.getByRole('button', { name: '가림 영역 지우기' }).click()
  await expect(page.getByTestId('redact-box')).toHaveCount(0)
  await expect(page.locator('.te-status')).toContainText('가림 영역 0곳 · 저장됨')
  expect(await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/redactions`)).json()).toHaveLength(0)
})
