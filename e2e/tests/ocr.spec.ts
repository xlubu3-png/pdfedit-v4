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

test('the text editor recognizes the page it shows, outlines what it found, and steps to the next page', async ({ page }) => {
  await page.goto('/')
  const pdf = await scan(page)
  await page.locator('input[type=file]').setInputFiles([
    { name: 'scan.pdf', mimeType: 'application/pdf', buffer: pdf },
    { name: 'typed.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Real text line']) },
  ])
  await expect(page.locator('.page-card')).toHaveCount(2)
  await page.getByRole('button', { name: '나중에' }).click()

  await page.locator('button[title="텍스트 편집"]').first().click()
  const pager = page.getByRole('group', { name: '쪽 이동' })
  await expect(pager).toContainText('1 / 2')
  await expect(page.locator('.te-title')).toContainText('scan.pdf')

  const editor = page.getByRole('dialog', { name: '텍스트 편집' })
  await editor.getByRole('button', { name: '글자 인식(OCR)' }).click()
  await expect(page.locator('.te-ocr-note')).toContainText('1쪽에서 글자 2줄을 인식했습니다', {
    timeout: 60_000,
  })
  await expect(page.locator('.te-ocr-line')).toHaveCount(2)
  await editor.getByRole('button', { name: '인식 표시' }).click()
  await expect(page.locator('.te-ocr-line')).toHaveCount(0)

  await pager.getByRole('button', { name: '다음 쪽' }).click()
  await expect(page.locator('.te-title')).toContainText('typed.pdf')
  await expect(pager).toContainText('2 / 2')
  await expect(page.locator('.te-box[data-index="0"]')).toHaveText('Real text line')
  await expect(pager.getByRole('button', { name: '다음 쪽' })).toBeDisabled()

  // Back on the first page the lines found earlier are outlined again, and the page list marks it.
  await pager.getByRole('button', { name: '이전 쪽' }).click()
  await expect(page.locator('.te-ocr-line')).toHaveCount(2)
  await page.locator('.rb-back').click()
  await expect(page.locator('.page-card .page-ocr')).toHaveCount(1)
})

test('a recognized line is copied with Ctrl+C, and a double click rewrites it so the export says the new text', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: await scan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  const editor = page.getByRole('dialog', { name: '텍스트 편집' })
  await editor.getByRole('button', { name: '글자 인식(OCR)' }).click()
  const phone = page.locator('.te-ocr-line[data-ocr="1"]')
  await expect(phone).toBeVisible({ timeout: 60_000 })

  await phone.click()
  await expect(phone).toHaveClass(/picked/)
  await page.keyboard.press('Control+c')
  await expect(page.locator('.te-status')).toContainText('인식한 글자 1줄을 복사했습니다')
  expect((await page.evaluate(() => navigator.clipboard.readText())).replace(/\s+/g, '')).toBe('연락처010-2345-6789')

  await phone.dblclick()
  const box = page.locator('.te-box[data-added]')
  await expect(box).toBeFocused()
  expect((await box.textContent())?.replace(/\s+/g, '')).toBe('연락처010-2345-6789')
  await expect(page.locator('.te-ocr-line')).toHaveCount(1) // the rewritten line gives way to its box
  await page.keyboard.press('Control+a')
  await page.keyboard.type('연락처 010-9999-0000')
  await page.locator('.te-layer').click({ position: { x: 5, y: 5 } })
  await expect(page.locator('.te-status')).toContainText('반영됨')

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    editor.getByRole('button', { name: 'PDF로 내보내기' }).click(),
  ])
  const text = await textOf(page, readFileSync(await download.path()))
  expect(text).toContain('010-9999-0000')
  expect(text).toContain('스캔문서글자인식시험')
  expect(text).not.toContain('2345')
})
