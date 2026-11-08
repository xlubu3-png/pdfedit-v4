import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scannedPdf } from './fixtures'

/** A scan fed 2 degrees askew: lines of text and a ruled list drawn on a 200 dpi A4 canvas, turned anticlockwise. */
async function askewScan(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 1654
    canvas.height = 2339
    const g = canvas.getContext('2d')!
    g.fillStyle = '#fff'
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.translate(827, 1170)
    g.rotate((-2 * Math.PI) / 180)
    g.translate(-827, -1170)
    g.fillStyle = '#000'
    g.strokeStyle = '#222'
    g.lineWidth = 3
    g.font = '44px "Malgun Gothic", sans-serif'
    const rows = ['품목  AIR SHOWER  1대', '연락처 010-2345-6789', '납기  발주 후 2주일', '결제  현금 결제']
    rows.forEach((text, i) => {
      g.fillText(text, 220, 400 + i * 110)
      g.beginPath()
      g.moveTo(200, 425 + i * 110)
      g.lineTo(1450, 425 + i * 110)
      g.stroke()
    })
    return canvas.toDataURL('image/jpeg', 0.92)
  })
  return scannedPdf(Buffer.from(dataUrl.split(',')[1], 'base64'), 1654, 2339)
}

/** The document the first page in the list comes from, as its thumbnail is fetched. */
async function firstPageDocument(page: Page): Promise<string> {
  const src = await page.locator('.page-card img').first().getAttribute('src')
  return /documents\/([^/]+)\//.exec(src ?? '')![1]
}

async function setStraighten(page: Page, on: boolean) {
  const now = await (await page.request.get('/api/v1/pdf/settings')).json()
  expect((await page.request.put('/api/v1/pdf/settings', { data: { ...now, straightenOnOcr: on } })).ok()).toBeTruthy()
}

test('recognizing a scan fed askew sets its page straight first, and Ctrl+Z takes that back', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'askew.pdf', mimeType: 'application/pdf', buffer: await askewScan(page) })
  await expect(page.getByText('스캔한 문서로 보입니다: askew.pdf')).toBeVisible()
  const before = await firstPageDocument(page)

  await page.getByRole('button', { name: '글자 인식', exact: true }).click()

  const banner = page.locator('.info-banner[role=status]').last()
  await expect(banner).toContainText('1쪽의 기울기를 바로잡았습니다', { timeout: 60_000 })
  await expect(banner).toContainText(/글자 \d+줄을 인식했습니다/)
  const after = await firstPageDocument(page)
  expect(after).not.toBe(before)
  // Read off the straight page, the lines lie level.
  const lines = (await (await page.request.get(`/api/v1/pdf/documents/${after}/pages/0/ocr`)).json()).lines
  expect(lines.map((l: { text: string }) => l.text.replace(/\s+/g, '')).join('|')).toContain('010-2345-6789')
  for (const line of lines) expect(Math.abs(line.tilt ?? 0)).toBeLessThan(0.5)

  await page.locator('.page-grid').click({ position: { x: 2, y: 2 } })
  await page.keyboard.press('Control+z')
  await expect.poll(() => firstPageDocument(page)).toBe(before)
})

test('the page list straightens a page from its menu, and leaves a straight one as it is', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'askew.pdf', mimeType: 'application/pdf', buffer: await askewScan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  const before = await firstPageDocument(page)

  await page.locator('.page-card').first().click({ button: 'right' })
  await page.getByRole('menuitem', { name: /기울기 바로잡기/ }).click()
  await expect(page.locator('.info-banner[role=status]').last()).toContainText('1쪽의 기울기를 바로잡았습니다')
  const once = await firstPageDocument(page)
  expect(once).not.toBe(before)

  // Straight now: nothing more to do.
  await page.locator('.page-card').first().click({ button: 'right' })
  await page.getByRole('menuitem', { name: /기울기 바로잡기/ }).click()
  await expect(page.locator('.info-banner[role=status]').last()).toContainText('바로잡을 만큼 기운 스캔 쪽이 없습니다')
  expect(await firstPageDocument(page)).toBe(once)
})

test('with straightening switched off in the settings, a scan is read as it is', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '글자 인식 설정' }).click()
  const dialog = page.getByRole('dialog', { name: '글자 인식 설정' })
  await expect(dialog.getByRole('checkbox')).toBeChecked()
  await dialog.getByRole('checkbox').uncheck()
  await dialog.getByRole('button', { name: '저장' }).click()
  await expect(dialog).toHaveCount(0)
  try {
    await page.locator('input[type=file]').setInputFiles({ name: 'askew.pdf', mimeType: 'application/pdf', buffer: await askewScan(page) })
    const before = await firstPageDocument(page)
    await page.getByRole('button', { name: '글자 인식', exact: true }).click()
    const banner = page.locator('.info-banner[role=status]').last()
    await expect(banner).toContainText(/글자 \d+줄을 인식했습니다/, { timeout: 60_000 })
    await expect(banner).not.toContainText('기울기')
    expect(await firstPageDocument(page)).toBe(before)
  } finally {
    await setStraighten(page, true)
  }
})

test('the editor sets an askew page straight before reading it, and shows the straight page read', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'askew.pdf', mimeType: 'application/pdf', buffer: await askewScan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  const before = await firstPageDocument(page)
  await page.locator('button[title="텍스트 편집"]').first().click()

  await page.locator('.te-ocr-ask').getByRole('button', { name: '글자 인식' }).click()

  await expect(page.locator('.te-ocr-line').first()).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('1쪽의 기울기를 바로잡았습니다')).toBeVisible()
  const src = await page.locator('.te-canvas img').first().getAttribute('src')
  expect(src).not.toContain(before)
})
