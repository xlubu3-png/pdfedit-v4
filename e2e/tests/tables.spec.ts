import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scannedPdf, tablePdf } from './fixtures'

const QUOTE = [
  ['NO', 'Part', 'Qty', 'Amount'],
  ['1', '1-A', '5', '220,300'],
  ['2', '1-B', '', '19,770'],
  ['Total', '240,070'],
]

async function openTable(page: Page) {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'quote.pdf',
    mimeType: 'application/pdf',
    buffer: tablePdf(QUOTE, [40, 80, 60, 90]),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
}

/** What is on the clipboard: its HTML (what Excel pastes) and its plain text. */
async function clipboard(page: Page): Promise<{ html: string; text: string }> {
  return page.evaluate(async () => {
    const [item] = await navigator.clipboard.read()
    const read = async (type: string) => (item.types.includes(type) ? (await item.getType(type)).text() : '')
    return { html: await read('text/html'), text: await read('text/plain') }
  })
}

test('a table drawn with lines is copied into its cells: empty cells kept, merged cells merged', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await openTable(page)

  await page.locator('.te-layer').click({ button: 'right', position: { x: 300, y: 700 } })
  await page.getByRole('menuitem', { name: '표 모양으로 모두 복사 (엑셀)' }).click()
  await expect(page.locator('.te-status')).toContainText('엑셀에 붙이면')

  const { html, text } = await clipboard(page)
  expect(text.replace(/\r\n/g, '\n').split('\n')).toEqual([
    'NO\tPart\tQty\tAmount',
    '1\t1-A\t5\t220,300',
    '2\t1-B\t\t19,770',
    'Total\t240,070',
  ])
  expect(html).toContain('colspan="3"')
  expect(html).toContain('border-top:.5pt solid #000')
  // "1-A" stays text in Excel instead of turning into a date.
  expect(html).toMatch(/mso-number-format:'\\@'[^>]*>1-A</)
})

test('Ctrl+C on cells dragged over in text selection carries the table for Excel too', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await openTable(page)

  await page.keyboard.press('s')
  const spans = page.locator('.te-textlayer span[data-line]')
  const from = (await spans.filter({ hasText: /^1-A$/ }).boundingBox())!
  const to = (await spans.filter({ hasText: /^19,770$/ }).boundingBox())!
  await page.mouse.move(from.x + 1, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width - 1, to.y + to.height / 2, { steps: 8 })
  await page.mouse.up()
  await page.keyboard.press('Control+c')

  const { html, text } = await clipboard(page)
  // Notepad still gets the letters as lines; Excel gets the picked part of the table.
  expect(text).toContain('1-A')
  expect(html).toContain('<table')
  expect(html).toMatch(/>1-A<\/td>/)
  expect(html).toMatch(/>19,770<\/td>/)
  expect(html).not.toContain('>NO<')
})

test('in the editor, Ctrl+C with nothing picked copies the page with its table, and a picked line goes out as text', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await openTable(page)

  await page.locator('.te-layer').click({ position: { x: 5, y: 5 } })
  await page.keyboard.press('Control+c')
  await expect(page.locator('.te-status')).toContainText('엑셀에 붙이면')
  const all = await clipboard(page)
  expect(all.html).toContain('colspan="3"')
  expect(all.text.replace(/\r\n/g, '\n')).toContain('2\t1-B\t\t19,770')

  // A picked line: copied in the app (Ctrl+V pastes the line) and to the computer's clipboard as its letters.
  await page.locator('.te-box', { hasText: '220,300' }).first().click()
  await page.keyboard.press('Control+c')
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe('220,300')
})

test('the page is saved as an Excel file laid out as the page: from the editor and from the page list', async ({ page }) => {
  await openTable(page)

  const [fromEditor] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('.te-layer').click({ button: 'right', position: { x: 300, y: 700 } }).then(() =>
      page.getByRole('menuitem', { name: /엑셀 파일로 저장/ }).click(),
    ),
  ])
  expect(fromEditor.suggestedFilename()).toBe('quote.xlsx')
  const editorFile = readFileSync((await fromEditor.path())!)
  expect(editorFile.subarray(0, 2).toString('latin1')).toBe('PK')
  // Stored, not compressed: the sheet's XML is readable in the file.
  const sheet = editorFile.toString('utf8')
  expect(sheet).toContain('<mergeCell ref="B4:D4"/>')
  expect(sheet).toContain('<v>220300</v>')
  expect(sheet).toContain('>1-A</t>')

  await page.locator('.rb-back').click()
  const [fromList] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '엑셀로 내보내기' }).click()])
  expect(fromList.suggestedFilename()).toBe('quote.xlsx')
  await expect(page.locator('.info-banner')).toContainText('quote.xlsx로 저장했습니다')
})

test('a scanned table (lines only in the picture) is recognized and saved to Excel in its cells', async ({ page }) => {
  await page.goto('/')
  // A 200 dpi scan of a 3-column table: its borders and text are pixels only.
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 1654
    canvas.height = 2339
    const g = canvas.getContext('2d')!
    g.fillStyle = '#fff'
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.strokeStyle = '#222'
    g.lineWidth = 3
    const xs = [200, 600, 1000, 1400]
    const ys = [300, 400, 500, 600]
    for (const y of ys) {
      g.beginPath()
      g.moveTo(xs[0], y)
      g.lineTo(xs[3], y)
      g.stroke()
    }
    for (const x of xs) {
      g.beginPath()
      g.moveTo(x, ys[0])
      g.lineTo(x, ys[3])
      g.stroke()
    }
    g.fillStyle = '#000'
    g.font = '44px sans-serif'
    const cells = [
      ['MODEL', 'K8', '2021'],
      ['ENGINE', 'G4KN', '2497'],
      ['OWNER', 'WINTECH', '1865'],
    ]
    cells.forEach((row, r) => row.forEach((text, c) => g.fillText(text, xs[c] + 30, ys[r] + 66)))
    return canvas.toDataURL('image/jpeg', 0.92)
  })
  await page.locator('input[type=file]').setInputFiles({
    name: 'form.pdf',
    mimeType: 'application/pdf',
    buffer: scannedPdf(Buffer.from(dataUrl.split(',')[1], 'base64'), 1654, 2339),
  })
  await page.getByRole('button', { name: '나중에' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  await page.locator('.te-ocr-ask').getByRole('button', { name: '글자 인식' }).click()
  await expect(page.locator('.te-ocr-line').first()).toBeVisible({ timeout: 60_000 })

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } }).then(() =>
      page.getByRole('menuitem', { name: /엑셀 파일로 저장/ }).click(),
    ),
  ])
  const sheet = readFileSync((await download.path())!).toString('utf8')
  // The table's three columns are three sheet columns: each value in the cell of its column, with borders.
  const cell = (text: string) => new RegExp(`<c r="([A-Z]+)(\\d+)"[^>]*t="inlineStr"><is><t[^>]*>${text}</t>`).exec(sheet)
  const model = cell('M ?ODEL') // recognition may put a space inside a word
  const k8 = cell('K8')
  const owner = cell('OW ?NER')
  expect(model && k8 && owner).toBeTruthy()
  expect(k8![1]).not.toBe(model![1])
  expect(owner![1]).toBe(model![1])
  expect(Number(owner![2]) - Number(model![2])).toBe(2)
  expect(sheet).toContain('<v>1865</v>')
})
