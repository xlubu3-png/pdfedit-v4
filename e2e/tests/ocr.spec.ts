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

test('the text editor recognizes every page at once and shows each page what was found on it', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles([
    { name: 'scan.pdf', mimeType: 'application/pdf', buffer: await scan(page) },
    { name: 'typed.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Real text line']) },
  ])
  await expect(page.locator('.page-card')).toHaveCount(2)
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').nth(1).click()
  const editor = page.getByRole('dialog', { name: '텍스트 편집' })

  await editor.getByRole('button', { name: '모든 쪽 인식' }).click()

  await expect(page.locator('.te-ocr-note')).toContainText('2쪽에서 글자 2줄을 인식했습니다', { timeout: 60_000 })
  await expect(page.locator('.te-ocr-note')).toContainText('1쪽은 이미 글자가 들어 있는 PDF입니다')
  await page.getByRole('group', { name: '쪽 이동' }).getByRole('button', { name: '이전 쪽' }).click()
  await expect(page.locator('.te-ocr-line')).toHaveCount(2)
  await page.locator('.rb-back').click()
  await expect(page.locator('.page-card .page-ocr')).toHaveCount(1)
})

test('a line running down the page is rewritten as a box turned the same way', async ({ page }) => {
  await page.goto('/')
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 1654
    canvas.height = 2339
    const g = canvas.getContext('2d')!
    g.fillStyle = '#fff'
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.fillStyle = '#000'
    g.font = '40px "Malgun Gothic"'
    g.fillText('성명 박장환', 400, 300)
    g.translate(250, 400)
    g.rotate(Math.PI / 2)
    g.fillText('연락처 010-2345-6789', 0, 0)
    return canvas.toDataURL('image/jpeg', 0.9)
  })
  await page.locator('input[type=file]').setInputFiles({
    name: 'turned.pdf',
    mimeType: 'application/pdf',
    buffer: scannedPdf(Buffer.from(dataUrl.split(',')[1], 'base64'), 1654, 2339),
  })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  await page.getByRole('dialog', { name: '텍스트 편집' }).getByRole('button', { name: '글자 인식(OCR)' }).click()
  const turned = page.locator('.te-ocr-line[title*="2345"]')
  await expect(turned).toBeVisible({ timeout: 60_000 })

  await turned.dblclick()

  const box = page.locator('.te-box[data-added]')
  await expect(box).toBeFocused()
  await expect(page.locator('.te-item.added')).toHaveCSS('transform', /matrix\(0, 1, -1, 0/)
  await page.keyboard.press('Control+a')
  await page.keyboard.type('연락처 010-9999-0000')
  await page.locator('.te-layer').click({ position: { x: 3, y: 3 } })
  await expect(page.locator('.te-status')).toContainText('반영됨')

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('dialog', { name: '텍스트 편집' }).getByRole('button', { name: 'PDF로 내보내기' }).click(),
  ])
  const text = await textOf(page, readFileSync(await download.path()))
  expect(text).toContain('010-9999-0000')
  expect(text).not.toContain('2345')
})

test('a recognized line opened with a double click and left unchanged goes back to the scan', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: await scan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  await page.getByRole('dialog', { name: '텍스트 편집' }).getByRole('button', { name: '글자 인식(OCR)' }).click()
  const phone = page.locator('.te-ocr-line[data-ocr="1"]')
  await expect(phone).toBeVisible({ timeout: 60_000 })

  await phone.dblclick()
  await expect(page.locator('.te-box[data-added]')).toBeFocused()
  await page.locator('.te-layer').click({ position: { x: 5, y: 5 } })

  await expect(page.locator('.te-box[data-added]')).toHaveCount(0)
  await expect(page.locator('.te-ocr-line')).toHaveCount(2)
  const src = await page.locator('.te-canvas img').first().getAttribute('src')
  const id = /documents\/([^/]+)\//.exec(src ?? '')![1]
  await expect
    .poll(async () => (await (await page.request.get(`/api/v1/pdf/documents/${id}/pages/0/text-runs`)).json()).added.length)
    .toBe(0)
})

test('a box rewritten in an older look is fitted to the scan again, keeping its text', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: await scan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  const editor = page.getByRole('dialog', { name: '텍스트 편집' })
  await editor.getByRole('button', { name: '글자 인식(OCR)' }).click()
  const phone = page.locator('.te-ocr-line[data-ocr="1"]')
  await expect(phone).toBeVisible({ timeout: 60_000 })
  await phone.dblclick()
  await expect(page.locator('.te-box[data-added]')).toBeFocused()
  await page.keyboard.press('Control+a')
  await page.keyboard.type('연락처 010-9999-0000')
  await page.locator('.te-layer').click({ position: { x: 5, y: 5 } })
  await expect(page.locator('.te-status')).toContainText('반영됨')
  const src = await page.locator('.te-canvas img').first().getAttribute('src')
  const id = /documents\/([^/]+)\//.exec(src ?? '')![1]
  const added = async () => (await (await page.request.get(`/api/v1/pdf/documents/${id}/pages/0/text-runs`)).json()).added
  const fitted = (await added())[0]

  // As an earlier version left it: another face, far too large and too light.
  const old = { ...fitted, fontFamily: 'Gulim', fontSize: fitted.fontSize * 1.6, color: '#a0a0a0' }
  expect((await page.request.put(`/api/v1/pdf/documents/${id}/pages/0/added-texts`, { data: [old] })).ok()).toBeTruthy()
  await editor.getByRole('button', { name: '페이지 목록' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-added]')).toBeVisible()

  await editor.getByRole('button', { name: '다시 맞추기' }).click()

  await expect(page.locator('.te-status')).toContainText('다시 맞췄습니다')
  await expect.poll(async () => (await added())[0].fontSize).toBeCloseTo(fitted.fontSize, 1)
  const again = (await added())[0]
  expect(again).toMatchObject({ id: fitted.id, text: '연락처 010-9999-0000', fontFamily: fitted.fontFamily, color: fitted.color })
})

test('in text selection a scan\'s recognized lines are all picked with Ctrl+A and copied', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: await scan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  const editor = page.getByRole('dialog', { name: '텍스트 편집' })
  await editor.getByRole('button', { name: '글자 인식(OCR)' }).click()
  await expect(page.locator('.te-ocr-line[data-ocr="1"]')).toBeVisible({ timeout: 60_000 })

  await editor.getByRole('button', { name: '글자 선택' }).first().click()
  await expect(page.locator('.te-textlayer span')).toHaveCount(2)
  await page.keyboard.press('Control+a')
  await page.keyboard.press('Control+c')

  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\s+/g, '')
  expect(copied).toBe('스캔문서글자인식시험연락처010-2345-6789')
})

test('recognized lines shown on the page are picked with a drag and copied, and still picked and rewritten with clicks', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: await scan(page) })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  await page.getByRole('dialog', { name: '텍스트 편집' }).getByRole('button', { name: '글자 인식(OCR)' }).click()
  const title = page.locator('.te-ocr-line[data-ocr="0"]')
  const phone = page.locator('.te-ocr-line[data-ocr="1"]')
  await expect(phone).toBeVisible({ timeout: 60_000 })

  // A drag from the first line to the end of the second, with no mode to turn on.
  const from = (await title.boundingBox())!
  const to = (await phone.boundingBox())!
  await page.mouse.move(from.x + 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width - 2, to.y + to.height / 2, { steps: 10 })
  await page.mouse.up()
  await page.keyboard.press('Control+c')
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')
  expect(copied.split('\n').map((l) => l.replace(/\s+/g, '')).filter(Boolean)).toEqual([
    '스캔문서글자인식시험',
    '연락처010-2345-6789',
  ])

  // A click still picks one line, and a double click rewrites it.
  await phone.click()
  await expect(phone).toHaveClass(/picked/)
  await page.keyboard.press('Control+c')
  expect((await page.evaluate(() => navigator.clipboard.readText())).replace(/\s+/g, '')).toBe('연락처010-2345-6789')
  await phone.dblclick()
  await expect(page.locator('.te-box[data-added]')).toBeFocused()
})

test('the right-click menu corrects a misread line, copies and redacts what is picked, and fits old boxes again', async ({
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
  const title = page.locator('.te-ocr-line[data-ocr="0"]')
  const phone = page.locator('.te-ocr-line[data-ocr="1"]')
  await expect(phone).toBeVisible({ timeout: 60_000 })
  const src = await page.locator('.te-canvas img').first().getAttribute('src')
  const id = /documents\/([^/]+)\//.exec(src ?? '')![1]
  const api = `/api/v1/pdf/documents/${id}/pages/0`

  // Correct what recognition read, the scan left alone.
  await phone.click({ button: 'right' })
  await page.getByRole('menuitem', { name: '인식 글자 고치기…' }).click()
  const fix = page.getByRole('textbox', { name: '이 줄의 글자' })
  await fix.fill('연락처 010-1111-2222')
  await fix.press('Enter')
  await expect
    .poll(async () => (await (await page.request.get(`${api}/ocr`)).json()).lines[1].text)
    .toBe('연락처 010-1111-2222')

  // Pick both lines with a drag; copy them as a table and black them out from the menu.
  const from = (await title.boundingBox())!
  const to = (await phone.boundingBox())!
  await page.mouse.move(from.x + 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width - 2, to.y + to.height / 2, { steps: 10 })
  await page.mouse.up()
  await phone.click({ button: 'right', position: { x: 10, y: to.height / 2 } })
  await page.getByRole('menuitem', { name: '표 모양으로 복사 (엑셀)' }).click()
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).split('\n').map((l) => l.replace(/\s+/g, ''))
  expect(copied).toEqual(['스캔문서글자인식시험', '연락처010-1111-2222'])

  await page.mouse.move(from.x + 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width - 2, to.y + to.height / 2, { steps: 10 })
  await page.mouse.up()
  await phone.click({ button: 'right', position: { x: 10, y: to.height / 2 } })
  await page.getByRole('menuitem', { name: '이 부분 가리기 (개인정보)' }).click()
  await expect.poll(async () => (await (await page.request.get(`${api}/redactions`)).json()).length).toBe(2)

  // A box rewritten by an earlier version is offered fitting again, and is fitted from the note.
  await phone.dblclick()
  await expect(page.locator('.te-box[data-added]')).toBeFocused()
  await page.keyboard.press('End')
  await page.keyboard.type('!')
  await page.locator('.te-layer').click({ position: { x: 5, y: 5 } })
  await expect(page.locator('.te-status')).toContainText('반영됨')
  const box = (await (await page.request.get(`${api}/text-runs`)).json()).added[0]
  expect(box.cover.fitting).toBe(1)
  // Changed while the editor is closed, so nothing it still holds is saved over it.
  await editor.getByRole('button', { name: '페이지 목록' }).click()
  await expect(editor).toHaveCount(0)
  const old = { ...box, cover: { ...box.cover, fitting: null }, fontSize: box.fontSize * 1.5 }
  expect((await page.request.put(`${api}/added-texts`, { data: [old] })).ok()).toBeTruthy()
  await page.locator('button[title="텍스트 편집"]').first().click()
  const note = page.locator('.te-old-note')
  await expect(note).toContainText('이전 버전에서 고쳐 쓴 줄이 1개')
  await note.getByRole('button', { name: '다시 맞추기' }).click()
  await expect(note).toHaveCount(0)
  await expect.poll(async () => (await (await page.request.get(`${api}/text-runs`)).json()).added[0].fontSize).toBeCloseTo(box.fontSize, 1)
})
