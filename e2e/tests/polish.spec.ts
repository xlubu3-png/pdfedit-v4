import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { tablePdf } from './fixtures'

const QUOTE = [
  ['NO', 'Part', 'Qty', 'Amount'],
  ['1', '1-A', '5', '220,300'],
  ['2', '1-B', '', '19,770'],
  ['Total', '240,070'],
]

test('the table taken out of a PDF is put right in 표 다듬기 and saved as Excel with what was changed', async ({ page }) => {
  const outside: string[] = []
  page.on('request', (r) => {
    const u = new URL(r.url())
    if (!['localhost', '127.0.0.1'].includes(u.hostname) && !['data:', 'blob:'].includes(u.protocol)) outside.push(u.origin)
  })
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'quote.pdf',
    mimeType: 'application/pdf',
    buffer: tablePdf(QUOTE, [40, 80, 60, 90]),
  })
  await expect(page.locator('.page-card')).toHaveCount(1)

  await page.getByRole('button', { name: '표 다듬기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '표 다듬기' })
  await expect(dialog).toContainText('quote.xlsx')
  const save = dialog.getByRole('button', { name: '엑셀로 저장' })
  await expect(save).toBeEnabled({ timeout: 30_000 })
  const frame = page.frames().find((f) => f.url().includes('/polish.html'))!
  // The sheet is the page's: the amount went over as a number, the heading as text.
  const book = await frame.evaluate(() => window.polish!.save())
  const cells = Object.values(book.sheets[book.sheetOrder[0]].cellData).flatMap((row) => Object.values(row).map((c) => c.v))
  expect(cells).toEqual(expect.arrayContaining(['Part', 220300, 19770]))

  // Typed as a user does: the quantity (C2) made 7, and a formula under the table (A6). A click on a cell gives
  // the spreadsheet the keys; the cells are where the table's columns put them.
  const box = (await page.getByTestId('polish-frame').boundingBox())!
  await page.mouse.click(box.x + 248, box.y + 174)
  await page.keyboard.type('7')
  await page.keyboard.press('Enter')
  await page.mouse.click(box.x + 75, box.y + 290)
  await page.keyboard.type('=C2*2')
  await page.keyboard.press('Enter')
  await expect.poll(() => frame.evaluate(() => window.polish!.changed())).toBe(true)
  await expect
    .poll(() => frame.evaluate(() => { const b = window.polish!.save(); return b.sheets[b.sheetOrder[0]].cellData[5]?.[0]?.v }))
    .toBe(14)

  const [download] = await Promise.all([page.waitForEvent('download'), save.click()])
  expect(download.suggestedFilename()).toBe('quote.xlsx')
  const xlsx = readFileSync((await download.path())!).toString('utf8')
  expect(xlsx).toContain('<v>7</v>')
  expect(xlsx).toContain('<f>C2*2</f><v>14</v>')
  expect(xlsx).toContain('<v>220300</v>')
  expect(xlsx).toContain('>Part<')
  await expect(dialog.locator('.te-status')).toContainText('quote.xlsx로 저장했습니다')

  // Saved: it closes without asking, and nothing went outside the PC.
  await dialog.getByRole('button', { name: '닫기', exact: true }).first().click()
  await expect(dialog).toHaveCount(0)
  expect(outside).toEqual([])
})

test('표 다듬기 opens from the editor for its page, and asks before unsaved changes are closed away', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'quote.pdf',
    mimeType: 'application/pdf',
    buffer: tablePdf(QUOTE, [40, 80, 60, 90]),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await page.getByRole('tab', { name: '파일' }).click()
  await page.locator('.te-overlay').first().getByRole('button', { name: '표 다듬기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '표 다듬기' })
  await expect(dialog.getByRole('button', { name: '엑셀로 저장' })).toBeEnabled({ timeout: 30_000 })
  const frame = page.frames().find((f) => f.url().includes('/polish.html'))!

  // A click on a cell well inside the grid gives the spreadsheet the keys.
  const box = (await page.getByTestId('polish-frame').boundingBox())!
  await page.mouse.click(box.x + 300, box.y + 420)
  await page.keyboard.type('memo')
  await page.keyboard.press('Enter')
  await expect.poll(() => frame.evaluate(() => window.polish!.changed())).toBe(true)

  // Asked, and kept open when the answer is no; closed when it is yes. The editor under it took none of the keys.
  page.once('dialog', (d) => void d.dismiss())
  await dialog.getByRole('button', { name: '닫기', exact: true }).first().click()
  await expect(dialog).toBeVisible()
  page.once('dialog', (d) => void d.accept())
  await dialog.getByRole('button', { name: '닫기', exact: true }).first().click()
  await expect(dialog).toHaveCount(0)
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await expect(page.locator('.te-added')).toHaveCount(0)
})
