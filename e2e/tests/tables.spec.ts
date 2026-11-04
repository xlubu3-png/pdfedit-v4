import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { tablePdf } from './fixtures'

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
