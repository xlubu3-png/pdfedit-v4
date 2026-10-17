import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { simplePdf, solidPng } from './fixtures'

/** Opens the editor on the first page of a freshly uploaded one-page PDF with these lines. */
async function openEditor(page: Page, lines: string[]): Promise<string> {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'sample.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(lines),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  const src = await page.locator('.te-canvas img').getAttribute('src')
  return /documents\/([^/]+)\//.exec(src ?? '')![1]
}

/** What the server holds for the page: the lines and what was done to them. */
async function serverPage(page: Page, documentId: string) {
  const response = await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/text-runs`)
  expect(response.ok()).toBeTruthy()
  return response.json()
}

/** Selects a line and starts typing in it (a click selects, Enter starts the typing). */
async function startTyping(page: Page, index: number) {
  await page.locator(`.te-box[data-index="${index}"]`).click()
  await page.keyboard.press('Enter')
  await expect(page.locator(`.te-box[data-index="${index}"]`)).toHaveClass(/typing/)
}

async function waitSaved(page: Page) {
  await expect(page.locator('.te-status')).toContainText('반영됨')
}

test('a line that is typed over is saved and ends up in the exported PDF', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World', 'Second line'])

  await startTyping(page, 0)
  await page.keyboard.press('Control+a')
  await page.keyboard.type('Goodbye World')
  await page.keyboard.press('Enter') // leaves the line, which sends it
  await waitSaved(page)

  const saved = await serverPage(page, documentId)
  expect(saved.runs[0].currentText).toBe('Goodbye World')
  expect(saved.runs[0].edited).toBe(true)
  expect(saved.runs[1].edited).toBe(false)

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('.te-overlay').getByRole('button', { name: 'PDF로 내보내기' }).click(),
  ])
  const exported = readFileSync(await download.path())
  expect(exported.subarray(0, 5).toString('latin1')).toBe('%PDF-')

  // Read the exported file back the way a reader would: its text is the edited text.
  const again = await page.request.post('/api/v1/pdf/documents', {
    multipart: { file: { name: 'exported.pdf', mimeType: 'application/pdf', buffer: exported } },
  })
  const { documentId: exportedId } = await again.json()
  const texts = (await serverPage(page, exportedId)).runs.map((r: { text: string }) => r.text).join(' ')
  expect(texts).toContain('Goodbye World')
  expect(texts).not.toContain('Hello World')
  expect(texts).toContain('Second line')
})

test('undo brings the original line back', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World'])

  await startTyping(page, 0)
  await page.keyboard.press('Control+a')
  await page.keyboard.type('Changed')
  await page.keyboard.press('Enter')
  await waitSaved(page)
  expect((await serverPage(page, documentId)).runs[0].currentText).toBe('Changed')

  await page.locator('.te-header button[title^="실행 취소"]').click()
  await expect.poll(async () => (await serverPage(page, documentId)).runs[0].currentText).toBe('Hello World')
})

test('only the letters picked turn bold, coloured, bigger and underlined', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World'])

  await startTyping(page, 0)
  await page.keyboard.press('Home')
  for (let i = 0; i < 5; i++) await page.keyboard.press('Shift+ArrowRight') // "Hello"
  await page.getByRole('button', { name: /굵게/ }).click()
  await page.getByRole('button', { name: /밑줄/ }).click()
  await waitSaved(page)

  await expect(page.locator('.te-style-info')).toContainText('부분 서식 1곳')
  const spans = (await serverPage(page, documentId)).runs[0].editSpans
  expect(spans).toEqual([expect.objectContaining({ start: 0, end: 5, bold: true, underline: true })])

  // With the letters still picked, the size box sets their size - not the line's.
  await page.getByLabel('글자 크기 (pt)').fill('36')
  await expect.poll(async () => (await serverPage(page, documentId)).runs[0].editSpans[0]?.size).toBe(36)
  expect((await serverPage(page, documentId)).runs[0].editFontSize).toBeNull()
  // Using the size box took the focus out of the line: a click on the selected line goes back into it.
  await page.locator('.te-box[data-index="0"]').click()
  await expect(page.locator('.te-box[data-index="0"]')).toHaveClass(/typing/)

  // Another stretch gets a colour of its own: the first one is left as it was.
  await page.keyboard.press('End')
  for (let i = 0; i < 5; i++) await page.keyboard.press('Shift+ArrowLeft') // "World"
  const colour = page.locator('.te-stylebar input[type=color]')
  // Wait until all of "World" is picked (and the page has taken note of it) before using the colour box.
  await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe('World')
  await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))))
  await colour.fill('#ff0000')
  await expect.poll(async () => (await serverPage(page, documentId)).runs[0].editSpans.length).toBe(2)
  const both = (await serverPage(page, documentId)).runs[0].editSpans
  expect(both[0]).toMatchObject({ start: 0, end: 5, bold: true })
  expect(both[1]).toMatchObject({ start: 6, end: 11, color: '#ff0000' })
  // the line itself was not restyled
  expect((await serverPage(page, documentId)).runs[0].editColor).toBeNull()
})

test('a picture is added, resized, saved with the page and deleted', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World'])

  await page.getByTestId('image-input').setInputFiles({
    name: 'stamp.png',
    mimeType: 'image/png',
    buffer: solidPng(60, 30, [200, 0, 0]),
  })
  const frame = page.locator('.te-image')
  await expect(frame).toBeVisible()
  await waitSaved(page)

  let images = (await serverPage(page, documentId)).images
  expect(images).toHaveLength(1)
  expect(images[0].width).toBeCloseTo(45, 1) // 60 px at 96 dpi
  expect(images[0].height).toBeCloseTo(22.5, 1)

  // the width box of the picture bar resizes it, the proportions kept
  const width = page.getByLabel('그림 너비 (pt)')
  await width.fill('90')
  await expect.poll(async () => (await serverPage(page, documentId)).images[0].width).toBeCloseTo(90, 1)
  images = (await serverPage(page, documentId)).images
  expect(images[0].height).toBeCloseTo(45, 1)

  // the picture itself is served back
  const served = await page.request.get(`/api/v1/pdf/documents/${documentId}/images/${images[0].imageId}`)
  expect(served.headers()['content-type']).toContain('image/png')

  await page.locator('.te-stylebar').getByRole('button', { name: '삭제', exact: true }).click()
  await expect(frame).toHaveCount(0)
  await expect.poll(async () => (await serverPage(page, documentId)).images).toHaveLength(0)
})

test('a picture on the clipboard is pasted onto the page with Ctrl+V', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World'])

  // What the browser does for a copied picture: a paste event whose clipboard holds an image file.
  await page.evaluate((base64) => {
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
    const data = new DataTransfer()
    data.items.add(new File([bytes], 'clip.png', { type: 'image/png' }))
    document.body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }))
  }, solidPng(80, 40, [0, 0, 200]).toString('base64'))

  await expect(page.locator('.te-image')).toBeVisible()
  await expect.poll(async () => (await serverPage(page, documentId)).images.length).toBe(1)
  expect((await serverPage(page, documentId)).images[0].width).toBeCloseTo(60, 1) // 80 px at 96 dpi
})

test('a formatted line keeps its formatting when it is duplicated, copied and pasted', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World'])

  await startTyping(page, 0)
  await page.keyboard.press('Home')
  for (let i = 0; i < 5; i++) await page.keyboard.press('Shift+ArrowRight') // "Hello"
  await page.getByRole('button', { name: /굵게/ }).click()
  await waitSaved(page)
  await page.keyboard.press('Escape') // out of the line, which stays selected

  await page.keyboard.press('Control+d')
  await expect(page.locator('.te-box[data-added]')).toHaveCount(1)
  await expect.poll(async () => (await serverPage(page, documentId)).added.length).toBe(1)
  let added = (await serverPage(page, documentId)).added
  expect(added[0].text).toBe('Hello World')
  expect(added[0].spans).toEqual([expect.objectContaining({ start: 0, end: 5, bold: true })])

  // the duplicate is selected: copy it and paste, which makes a third with the same parts
  await page.keyboard.press('Control+c')
  await page.keyboard.press('Control+v')
  await expect.poll(async () => (await serverPage(page, documentId)).added.length).toBe(2)
  added = (await serverPage(page, documentId)).added
  expect(added[1].spans).toEqual(added[0].spans)

  // typing before the formatted part moves it along with its letters
  // (the pasted box is on top and selected, so a click on it goes straight into typing)
  const pasted = page.locator('.te-box[data-added]').last()
  await pasted.click()
  await expect(pasted).toHaveClass(/typing/)
  await page.keyboard.press('Control+Home')
  await page.keyboard.type('>> ')
  await page.keyboard.press('Escape')
  await expect.poll(async () => (await serverPage(page, documentId)).added[1].text).toBe('>> Hello World')
  expect((await serverPage(page, documentId)).added[1].spans).toEqual([
    expect.objectContaining({ start: 3, end: 8, bold: true }),
  ])
})

test('letters picked inside a text box turn bold, underlined and coloured on their own', async ({ page }) => {
  const documentId = await openEditor(page, ['Hello World'])

  await page.getByRole('button', { name: /텍스트 추가/ }).click()
  await page.locator('.te-layer').click({ position: { x: 300, y: 400 } })
  const box = page.locator('.te-box[data-added]')
  await expect(box).toHaveClass(/typing/)
  await page.keyboard.type('Hello World')
  await page.keyboard.press('Home')
  for (let i = 0; i < 5; i++) await page.keyboard.press('Shift+ArrowRight') // "Hello"
  await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe('Hello')
  await page.getByRole('button', { name: /굵게/ }).click()
  await page.getByRole('button', { name: /밑줄/ }).click()
  await waitSaved(page)

  await expect(page.locator('.te-style-info')).toContainText('부분 서식 1곳')
  let added = (await serverPage(page, documentId)).added
  expect(added[0].text).toBe('Hello World')
  expect(added[0].bold).toBe(false) // the box itself is not bold
  expect(added[0].spans).toEqual([expect.objectContaining({ start: 0, end: 5, bold: true, underline: true })])

  // another stretch gets a colour of its own, the first one stays as it was
  await page.keyboard.press('End')
  for (let i = 0; i < 5; i++) await page.keyboard.press('Shift+ArrowLeft') // "World"
  await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe('World')
  await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))))
  await page.locator('.te-stylebar input[type=color]').fill('#ff0000')
  await expect.poll(async () => (await serverPage(page, documentId)).added[0].spans.length).toBe(2)
  added = (await serverPage(page, documentId)).added
  expect(added[0].spans[0]).toMatchObject({ start: 0, end: 5, bold: true, underline: true })
  expect(added[0].spans[1]).toMatchObject({ start: 6, end: 11, color: '#ff0000' })
  expect(added[0].color).toBe('#000000') // the box colour is untouched
})

test('a picture copied on one page is pasted onto a page of another document', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles([
    { name: 'first.pdf', mimeType: 'application/pdf', buffer: simplePdf(['First page']) },
    { name: 'second.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Second page']) },
  ])
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toHaveText('First page')
  const idOf = async () => /documents\/([^/]+)\//.exec((await page.locator('.te-canvas img').getAttribute('src')) ?? '')![1]
  const firstId = await idOf()

  await page.getByTestId('image-input').setInputFiles({
    name: 'stamp.png',
    mimeType: 'image/png',
    buffer: solidPng(60, 30, [200, 0, 0]),
  })
  await expect(page.locator('.te-image')).toBeVisible()
  await waitSaved(page)
  await page.keyboard.press('Control+c')
  await expect(page.locator('.te-status')).toContainText('그림을 복사했습니다')

  await page.locator('.te-header button[title^="다음 페이지"]').click()
  await expect(page.locator('.te-box[data-index="0"]')).toHaveText('Second page')
  const secondId = await idOf()
  expect(secondId).not.toBe(firstId)
  await page.keyboard.press('Control+v')

  await expect(page.locator('.te-image')).toBeVisible()
  await expect.poll(async () => (await serverPage(page, secondId)).images.length).toBe(1)
  const first = (await serverPage(page, firstId)).images
  const second = (await serverPage(page, secondId)).images
  expect(first).toHaveLength(1)
  expect(second[0].width).toBeCloseTo(first[0].width, 1)
  // the picture was uploaded again to the second document, which serves it itself
  expect(second[0].imageId).not.toBe(first[0].imageId)
  const served = await page.request.get(`/api/v1/pdf/documents/${secondId}/images/${second[0].imageId}`)
  expect(served.ok()).toBeTruthy()
})

test('a picture copied on one page is pasted onto another page of the same document, as the same picture', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'two.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['A page'], 2),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toHaveText('A page')
  const documentId = /documents\/([^/]+)\//.exec((await page.locator('.te-canvas img').getAttribute('src')) ?? '')![1]

  await page.getByTestId('image-input').setInputFiles({
    name: 'stamp.png',
    mimeType: 'image/png',
    buffer: solidPng(60, 30, [200, 0, 0]),
  })
  await expect(page.locator('.te-image')).toBeVisible()
  await waitSaved(page)
  await page.keyboard.press('Control+c')

  await page.locator('.te-header button[title^="다음 페이지"]').click()
  await expect(page.locator('.te-header')).toContainText('2 / 2')
  await expect(page.locator('.te-box[data-index="0"]')).toHaveText('A page') // the page has been read
  await page.keyboard.press('Control+v')
  await expect(page.locator('.te-image')).toBeVisible()

  const secondPage = async () =>
    (await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/1/text-runs`)).json()).images
  await expect.poll(async () => (await secondPage()).length).toBe(1)
  const onFirst = (await serverPage(page, documentId)).images
  // the same document keeps one copy of the picture and both pages point at it
  expect((await secondPage())[0].imageId).toBe(onFirst[0].imageId)
})
